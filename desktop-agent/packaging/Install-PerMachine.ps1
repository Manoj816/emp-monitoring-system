<#
.SYNOPSIS
  Installs the EmpVerify agent for ALL users on this PC (per-machine).

.DESCRIPTION
  Run this on the employee's system. It works on any 64-bit Windows 10 (version
  1809 / build 17763 or newer) or Windows 11 PC, Home or Pro, with no other
  software pre-installed: the package carries its own .NET runtime, and this
  script adds the only external piece (the Microsoft WebView2 runtime) when it
  is missing. It elevates itself (UAC prompt) when not already running as
  Administrator.

  Steps:
    1. Preflight — Windows build, CPU architecture, sideloading of trusted apps.
    2. Trust the package signing certificate (dev-signed packages only).
    3. Install the WebView2 runtime if absent (the locked check window needs it).
    4. Write the provisioning token + backend URL (HKLM\SOFTWARE\EmpVerify) so the
       agent registers this system by itself on first launch.
    5. Provision the MSIX for every user on the PC (a standard user cannot remove
       a provisioned package) and install it for the current user.
    6. Start the agent as the signed-in user. It also auto-starts at every login.

  Defaults are picked up from the folder this script is in, so the bundle that
  build-msix.ps1 produces (out\ or the Setup zip) installs by double-clicking
  Install-EmpVerify.cmd: newest EmpVerify.Agent_*_x64.msix, empverify-dev.cer,
  and provisioning.json ({ "backendUrl": "...", "provisioningToken": "..." }).

.EXAMPLE
  .\Install-PerMachine.ps1
  .\Install-PerMachine.ps1 -ProvisioningToken (Read-Host) -BackendUrl http://empverify-alb-....elb.amazonaws.com
  .\Install-PerMachine.ps1 -MsixPath .\EmpVerify.Agent_0.5.0.0_x64.msix -TrustCert .\empverify-dev.cer
#>
[CmdletBinding()]
param(
    [string]$MsixPath,            # default: newest EmpVerify.Agent_*_x64.msix next to this script
    [string]$TrustCert,           # default: empverify-dev.cer next to this script, if present
    [string]$ProvisioningToken,   # default: provisioning.json next to this script
    [string]$BackendUrl,          # default: provisioning.json next to this script
    [switch]$SkipWebView2,        # do not install the WebView2 runtime when missing
    [switch]$NoLaunch             # do not start the agent after installing
)

$ErrorActionPreference = "Stop"
$PackageName = "EmpVerify.Agent"
$AppId = "EmpVerifyAgent"
$MinBuild = 17763
$WebView2Bootstrapper = "https://go.microsoft.com/fwlink/p/?LinkId=2124703"  # Microsoft Evergreen bootstrapper (permanent link)
$WebView2ClientKey = "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"

# ---------------------------------------------------------------- elevation
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object System.Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "Administrator rights are needed to install for all users - requesting elevation..."
    $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-NoExit", "-File", ('"{0}"' -f $PSCommandPath))
    foreach ($kv in $PSBoundParameters.GetEnumerator()) {
        if ($kv.Value -is [switch]) {
            if ($kv.Value) { $argList += "-$($kv.Key)" }
        } else {
            $argList += "-$($kv.Key)"
            $argList += ('"{0}"' -f $kv.Value)
        }
    }
    Start-Process powershell.exe -Verb RunAs -ArgumentList ($argList -join " ")
    return
}

# ---------------------------------------------------------------- defaults
if (-not $MsixPath) {
    $candidate = Get-ChildItem -Path $PSScriptRoot -Filter "${PackageName}_*_x64.msix" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $candidate) {
        $candidate = Get-ChildItem -Path (Join-Path $PSScriptRoot "out") -Filter "${PackageName}_*_x64.msix" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }
    if (-not $candidate) { throw "No ${PackageName}_*_x64.msix found next to this script. Pass -MsixPath." }
    $MsixPath = $candidate.FullName
}
$MsixPath = (Resolve-Path $MsixPath).Path

if (-not $TrustCert) {
    foreach ($dir in @($PSScriptRoot, (Join-Path $PSScriptRoot "out"))) {
        $cer = Join-Path $dir "empverify-dev.cer"
        if (Test-Path $cer) { $TrustCert = $cer; break }
    }
}

if (-not $ProvisioningToken) {
    $configPath = Join-Path $PSScriptRoot "provisioning.json"
    if (Test-Path $configPath) {
        $cfg = Get-Content $configPath -Raw | ConvertFrom-Json
        if ($cfg.provisioningToken -and $cfg.provisioningToken -notlike "<*") { $ProvisioningToken = [string]$cfg.provisioningToken }
        if (-not $BackendUrl -and $cfg.backendUrl) { $BackendUrl = [string]$cfg.backendUrl }
    }
}

Write-Host ""
Write-Host "EmpVerify agent - per-machine install"
Write-Host "  package : $MsixPath"
Write-Host "  cert    : $(if ($TrustCert) { $TrustCert } else { '(none - package must be signed by a trusted CA)' })"
Write-Host "  backend : $(if ($BackendUrl) { $BackendUrl } else { '(not set)' })"
Write-Host "  token   : $(if ($ProvisioningToken) { 'provided' } else { 'none' })"
Write-Host ""

# ---------------------------------------------------------------- 1. preflight
Write-Host "[1/6] Checking this PC..."
$build = [int](Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion").CurrentBuildNumber
$productName = (Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion").ProductName
if ($build -lt $MinBuild) {
    throw "Windows 10 version 1809 (build $MinBuild) or newer is required. This PC: $productName build $build."
}
$arch = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
switch ($arch) {
    "AMD64" { }
    "ARM64" {
        if ($build -lt 22000) { throw "This is an ARM64 PC on Windows 10, which cannot run x64 apps. Windows 11 is required on ARM64." }
        Write-Warning "ARM64 PC: the x64 package will run through Windows 11 x64 emulation."
    }
    default { throw "The package is 64-bit (x64); this PC reports a $arch processor." }
}
Write-Host "  $productName build $build, $arch - OK"

# Sideloading of trusted (non-Store) packages. On by default on current Windows;
# an older image or a policy may have it off.
$unlock = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\AppModelUnlock"
$allow = (Get-ItemProperty $unlock -ErrorAction SilentlyContinue).AllowAllTrustedApps
if ($allow -ne 1) {
    New-Item -Path $unlock -Force | Out-Null
    Set-ItemProperty -Path $unlock -Name AllowAllTrustedApps -Value 1 -Type DWord
    Write-Host "  enabled installation of trusted non-Store apps"
}

# ---------------------------------------------------------------- 2. certificate
Write-Host "[2/6] Signing certificate..."
if ($TrustCert) {
    $TrustCert = (Resolve-Path $TrustCert).Path
    $imported = Import-Certificate -FilePath $TrustCert -CertStoreLocation Cert:\LocalMachine\TrustedPeople
    Write-Host "  trusted $($imported.Subject) (LocalMachine\TrustedPeople)"
} else {
    Write-Host "  no certificate to import (expected for a package signed by a trusted CA)"
}

# ---------------------------------------------------------------- 3. WebView2
Write-Host "[3/6] WebView2 runtime (powers the check window)..."
function Get-WebView2Version {
    foreach ($key in @(
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\$WebView2ClientKey",
        "HKLM:\SOFTWARE\Microsoft\EdgeUpdate\Clients\$WebView2ClientKey",
        "HKCU:\SOFTWARE\Microsoft\EdgeUpdate\Clients\$WebView2ClientKey")) {
        $pv = (Get-ItemProperty $key -ErrorAction SilentlyContinue).pv
        if ($pv -and $pv -ne "0.0.0.0") { return $pv }
    }
    return $null
}
$wv2 = Get-WebView2Version
if ($wv2) {
    Write-Host "  present (version $wv2)"
} elseif ($SkipWebView2) {
    Write-Warning "WebView2 runtime is missing and -SkipWebView2 was given: checks will open in the default browser instead of the locked window."
} else {
    Write-Host "  missing - downloading the Microsoft Evergreen bootstrapper..."
    $setup = Join-Path $env:TEMP "MicrosoftEdgeWebview2Setup.exe"
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $WebView2Bootstrapper -OutFile $setup -UseBasicParsing
        $p = Start-Process -FilePath $setup -ArgumentList "/silent", "/install" -Wait -PassThru
        $wv2 = Get-WebView2Version
        if ($wv2) { Write-Host "  installed (version $wv2)" }
        else { Write-Warning "WebView2 installer exited with code $($p.ExitCode) and the runtime is still not detected. Install it from https://developer.microsoft.com/microsoft-edge/webview2/ and re-run." }
    } catch {
        Write-Warning "Could not download/install WebView2 ($($_.Exception.Message)). Install it from https://developer.microsoft.com/microsoft-edge/webview2/ - until then, checks open in the default browser."
    } finally {
        Remove-Item $setup -Force -ErrorAction SilentlyContinue
    }
}

# ---------------------------------------------------------------- 4. provisioning
Write-Host "[4/6] Provisioning..."
$regPath = "HKLM:\SOFTWARE\EmpVerify"
if ($ProvisioningToken) {
    New-Item -Path $regPath -Force | Out-Null
    Set-ItemProperty -Path $regPath -Name "ProvisioningToken" -Value $ProvisioningToken.Trim()
    if ($BackendUrl) { Set-ItemProperty -Path $regPath -Name "BackendUrl" -Value $BackendUrl.Trim() }
    Remove-ItemProperty -Path $regPath -Name "EnrollmentToken" -ErrorAction SilentlyContinue
    Write-Host "  token stored; the agent registers this system as 'Unassigned' on first launch"
} elseif (Test-Path $regPath) {
    Write-Host "  keeping the existing HKLM\SOFTWARE\EmpVerify configuration"
} else {
    Write-Warning "No provisioning token: the agent will ask for an enrollment token on first launch. Pass -ProvisioningToken/-BackendUrl or put provisioning.json next to this script."
}

# ---------------------------------------------------------------- 5. package
Write-Host "[5/6] Installing the package for all users..."
$existing = Get-AppxProvisionedPackage -Online | Where-Object DisplayName -eq $PackageName
if ($existing) {
    # Re-provision so the newest version is what future users receive.
    Remove-AppxProvisionedPackage -Online -PackageName $existing.PackageName | Out-Null
}
Add-AppxProvisionedPackage -Online -PackagePath $MsixPath -SkipLicense | Out-Null
Write-Host "  provisioned for every current and future user"
Add-AppxPackage -Path $MsixPath -ForceApplicationShutdown -ForceUpdateFromAnyVersion
$pkg = Get-AppxPackage -Name $PackageName
Write-Host "  installed for this user: $($pkg.PackageFullName)"

# ---------------------------------------------------------------- 6. launch
Write-Host "[6/6] Starting the agent..."
if ($NoLaunch) {
    Write-Host "  skipped (-NoLaunch); it starts automatically at the next sign-in"
} else {
    # Via the shell so it runs as the signed-in user, not elevated.
    Start-Process explorer.exe "shell:AppsFolder\$($pkg.PackageFamilyName)!$AppId"
    Write-Host "  started (tray icon); it also starts automatically at every sign-in"
}

Write-Host ""
Write-Host "Done. Next: in the admin portal, open Devices, find '$env:COMPUTERNAME' (Unassigned) and assign it to the employee."
Write-Host "Managed fleets: deploy the same .msix via Intune (line-of-business app) for central control and auto-update."
