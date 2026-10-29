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
$versionProps = @()
if ($Version) {
    # The agent reports its assembly version to the server: keep it equal to the package version.
    $versionProps = @("-p:AssemblyVersion=$Version", "-p:FileVersion=$Version", "-p:InformationalVersion=$Version")
}
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

# 5. Install bundle: everything an operator needs on the target PC, in out\ and
#    zipped. Copy the zip to the employee's system and double-click
#    Install-EmpVerify.cmd (after adding provisioning.json — see INSTALL.md).
Write-Host "[5/5] Assembling install bundle..."
$bundleFiles = @($msix)
if (-not $PfxPath) { $bundleFiles += (Join-Path $outDir "empverify-dev.cer") }
foreach ($name in @("Install-PerMachine.ps1", "Install-EmpVerify.cmd", "Uninstall.ps1", "Uninstall-EmpVerify.cmd",
                    "provisioning.sample.json", "INSTALL.md")) {
    $dst = Join-Path $outDir $name
    Copy-Item (Join-Path $PSScriptRoot $name) $dst -Force
    $bundleFiles += $dst
}
$zip = Join-Path $outDir "EmpVerify-Agent-Setup_${pkgVersion}.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $bundleFiles -DestinationPath $zip
Write-Host "  bundle: $zip"

Write-Host "`nDONE: $msix"
