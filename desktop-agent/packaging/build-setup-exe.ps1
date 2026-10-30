<#
.SYNOPSIS
  Builds the single-file installer EmpVerify-Agent-Setup-<version>.exe.

.DESCRIPTION
  Wraps an already-built MSIX (out\EmpVerify.Agent_<version>_x64.msix), the dev
  certificate and the install scripts into one Inno Setup executable with a
  small wizard (backend URL + provisioning token, prefilled when a
  packaging\provisioning.json exists). The administrator on the employee's PC
  just double-clicks the .exe. build-msix.ps1 calls this as its last step when
  an Inno Setup compiler is available.

  Inno Setup: install from https://jrsoftware.org/isdl.php (the portable
  extraction "/PORTABLE=1" is enough) and pass -Iscc, or set INNOSETUP_DIR.

.EXAMPLE
  ./build-setup-exe.ps1 -Version 0.8.0.0
  ./build-setup-exe.ps1 -Version 0.8.0.0 -Iscc "C:\Tools\InnoSetup\ISCC.exe"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Iscc,
    [string]$BackendUrl,
    [string]$ProvisioningToken
)

$ErrorActionPreference = "Stop"
$outDir = Join-Path $PSScriptRoot "out"
$msix = Join-Path $outDir "EmpVerify.Agent_${Version}_x64.msix"
if (-not (Test-Path $msix)) { throw "MSIX not found: $msix (run build-msix.ps1 -Version $Version first)" }

if (-not $Iscc) {
    foreach ($candidate in @(
        (Join-Path ($env:INNOSETUP_DIR | ForEach-Object { $_ }) "ISCC.exe"),
        "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
        "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
        "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe")) {
        if ($candidate -and (Test-Path $candidate)) { $Iscc = $candidate; break }
    }
}
if (-not $Iscc) { throw "ISCC.exe (Inno Setup 6) not found. Pass -Iscc or set INNOSETUP_DIR." }

# Wizard defaults: explicit parameters, else packaging\provisioning.json (git-ignored).
$config = Join-Path $PSScriptRoot "provisioning.json"
if ((-not $BackendUrl -or -not $ProvisioningToken) -and (Test-Path $config)) {
    $cfg = Get-Content $config -Raw | ConvertFrom-Json
    if (-not $BackendUrl -and $cfg.backendUrl) { $BackendUrl = [string]$cfg.backendUrl }
    if (-not $ProvisioningToken -and $cfg.provisioningToken -and $cfg.provisioningToken -notlike "<*") {
        $ProvisioningToken = [string]$cfg.provisioningToken
    }
}

$cert = Join-Path $outDir "empverify-dev.cer"
$defines = @(
    "/DVersion=$Version",
    "/DMsix=$msix",
    "/DMsixName=$(Split-Path $msix -Leaf)",
    "/DOutDir=$outDir"
)
if (Test-Path $cert) { $defines += @("/DCert=$cert", "/DCertName=empverify-dev.cer") }
if ($BackendUrl) { $defines += "/DBackendUrl=$BackendUrl" }
if ($ProvisioningToken) { $defines += "/DToken=$ProvisioningToken" }

Write-Host "Compiling EmpVerify-Agent-Setup-$Version.exe (token prefilled: $([bool]$ProvisioningToken))..."
& $Iscc /Q @defines (Join-Path $PSScriptRoot "EmpVerifySetup.iss")
if ($LASTEXITCODE -ne 0) { throw "ISCC failed ($LASTEXITCODE)" }

$exe = Join-Path $outDir "EmpVerify-Agent-Setup-$Version.exe"
Write-Host "DONE: $exe ($([math]::Round((Get-Item $exe).Length / 1MB, 1)) MB)"
