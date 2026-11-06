<#
.SYNOPSIS
  Builds the EmpVerify agent MSIX package.

.DESCRIPTION
  Publishes the WPF agent (self-contained win-x64, so the target PC needs no
  .NET), stages it with the MSIX manifest and logos, packs an .msix with
  makeappx, signs it, and assembles an install bundle (out\ and a Setup zip)
  with the install scripts for the employee's PC.

  Dev (default): creates/uses a self-signed certificate whose subject matches the
  manifest Publisher, signs the package, and exports the .cer so a machine can
  trust it for local install. NOT for production.

  Production: pass -PfxPath and -PfxPassword for a real code-signing certificate.
  The certificate subject MUST equal the manifest's Publisher attribute.

  Does not require admin. Installing the package per-machine does (see
  Install-PerMachine.ps1).

.EXAMPLE
  ./build-msix.ps1
  ./build-msix.ps1 -PfxPath C:\certs\empverify.pfx -PfxPassword (Read-Host -AsSecureString)
#>
[CmdletBinding()]
param(
    [string]$Configuration = "Release",
    [string]$Version,                    # overrides manifest version (x.x.x.x)
    [string]$MakeAppx,                   # auto-discovered if omitted
    [string]$SignTool,                   # auto-discovered if omitted
    [string]$PfxPath,                    # production signing cert
    [System.Security.SecureString]$PfxPassword,
    [string]$DotnetExe = "dotnet"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent                     # desktop-agent/
$proj = Join-Path $root "src/EmpVerify.Agent.Windows/EmpVerify.Agent.Windows.csproj"
$stage = Join-Path $PSScriptRoot "stage"
$outDir = Join-Path $PSScriptRoot "out"
$manifestSrc = Join-Path $PSScriptRoot "AppxManifest.xml"

function Find-SdkTool([string]$name, [string]$explicit) {
    if ($explicit) { return $explicit }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $kits = "C:\Program Files (x86)\Windows Kits\10\bin"
    if (Test-Path $kits) {
        $found = Get-ChildItem -Recurse -Filter $name $kits -ErrorAction SilentlyContinue |
            Where-Object FullName -match "x64" |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    throw "$name not found. Install the Windows SDK, or pass its path explicitly."
}

$makeappx = Find-SdkTool "makeappx.exe" $MakeAppx
$signtool = Find-SdkTool "signtool.exe" $SignTool
Write-Host "makeappx: $makeappx"
Write-Host "signtool: $signtool"

# 1. Publish the agent (self-contained so target machines need no .NET runtime).
Write-Host "`n[1/5] Publishing agent..."
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
# The agent reports its version to the server and the portal shows it per system, so
# the binary must always carry the package version - not only when -Version is passed
# by hand. CI runs this script without it, which is how every installed agent used to
# report the default 1.0.0.0. With no -Version, the manifest's own version is the one.
$effectiveVersion = $Version
if (-not $effectiveVersion) {
    [xml]$srcManifest = Get-Content (Join-Path $PSScriptRoot "AppxManifest.xml")
    $effectiveVersion = $srcManifest.Package.Identity.Version
}
$versionProps = @(
    "-p:AssemblyVersion=$effectiveVersion",
    "-p:FileVersion=$effectiveVersion",
    "-p:InformationalVersion=$effectiveVersion")
& $DotnetExe publish $proj -c $Configuration -r win-x64 --self-contained -o $stage --nologo @versionProps
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed" }

# 2. Stage the manifest + logos alongside the published binaries.
Write-Host "[2/5] Staging manifest and assets..."
$manifestDst = Join-Path $stage "AppxManifest.xml"
Copy-Item $manifestSrc $manifestDst -Force
Copy-Item (Join-Path $PSScriptRoot "Images") (Join-Path $stage "Images") -Recurse -Force

if ($Version) {
    [xml]$m = Get-Content $manifestDst
    $m.Package.Identity.Version = $Version
    $m.Save($manifestDst)
    Write-Host "  version set to $Version"
}
[xml]$manifest = Get-Content $manifestDst
$publisher = $manifest.Package.Identity.Publisher
$pkgVersion = $manifest.Package.Identity.Version

# 3. Pack.
Write-Host "[3/5] Packing MSIX..."
New-Item -ItemType Directory -Force $outDir | Out-Null
$msix = Join-Path $outDir "EmpVerify.Agent_${pkgVersion}_x64.msix"
if (Test-Path $msix) { Remove-Item $msix -Force }
& $makeappx pack /d $stage /p $msix /o
if ($LASTEXITCODE -ne 0) { throw "makeappx pack failed" }

# 4. Sign.
Write-Host "[4/5] Signing..."
if ($PfxPath) {
    $pw = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($PfxPassword))
    & $signtool sign /fd SHA256 /a /f $PfxPath /p $pw $msix
    if ($LASTEXITCODE -ne 0) { throw "signtool (pfx) failed" }
    Write-Host "Signed with production certificate: $PfxPath"
} else {
    # Dev self-signed path. Subject must match the manifest Publisher exactly.
    Write-Host "  dev self-signed (publisher: $publisher)"
    $cert = Get-ChildItem Cert:\CurrentUser\My | Where-Object { $_.Subject -eq $publisher } | Select-Object -First 1
    if (-not $cert) {
        $cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject $publisher `
            -CertStoreLocation Cert:\CurrentUser\My -KeyUsage DigitalSignature `
            -FriendlyName "EmpVerify Dev Signing" -NotAfter (Get-Date).AddYears(2)
        Write-Host "  created self-signed cert $($cert.Thumbprint)"
    }
    $pfx = Join-Path $outDir "empverify-dev.pfx"
    $cer = Join-Path $outDir "empverify-dev.cer"
    $devPw = ConvertTo-SecureString "empverify-dev" -AsPlainText -Force
    Export-PfxCertificate -Cert $cert -FilePath $pfx -Password $devPw | Out-Null
    Export-Certificate -Cert $cert -FilePath $cer | Out-Null
    & $signtool sign /fd SHA256 /f $pfx /p "empverify-dev" $msix
    if ($LASTEXITCODE -ne 0) { throw "signtool (dev) failed" }
    Write-Host "  exported trust cert: $cer (import to LocalMachine\TrustedPeople to install)"
}

# 5. Install bundle: everything an operator needs on the target PC and nothing
#    else, staged in out\bundle\. CI uploads that folder's *contents*, so the
#    download is one zip holding the files - not a zip inside a zip, which anyone
#    who unwraps it once ends up running with no package beside it. out\ itself is
#    never shippable: it holds the signing .pfx. The local zip is made from the
#    same folder, so the two can never disagree about what is in a bundle.
Write-Host "[5/5] Assembling install bundle..."
$bundleDir = Join-Path $outDir "bundle"
if (Test-Path $bundleDir) { Remove-Item $bundleDir -Recurse -Force }
New-Item -ItemType Directory -Path $bundleDir | Out-Null
Copy-Item $msix $bundleDir
if (-not $PfxPath) { Copy-Item (Join-Path $outDir "empverify-dev.cer") $bundleDir }
foreach ($name in @("Install-PerMachine.ps1", "Install-EmpVerify.cmd", "Uninstall.ps1", "Uninstall-EmpVerify.cmd",
                    "provisioning.sample.json", "INSTALL.md")) {
    Copy-Item (Join-Path $PSScriptRoot $name) $bundleDir -Force
}
$zip = Join-Path $outDir "EmpVerify-Agent-Setup_${pkgVersion}.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $bundleDir "*") -DestinationPath $zip
Write-Host "  bundle folder: $bundleDir"
Write-Host "  bundle zip   : $zip"

Write-Host "`nDONE: $msix"
