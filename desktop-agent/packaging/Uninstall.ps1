<#
.SYNOPSIS
  Removes the EmpVerify agent (admin offboarding / test cleanup).

.DESCRIPTION
  Elevates itself when needed. Stops the agent, removes the provisioned package
  (so it is not reinstalled for new users) and the installed package for the
  current user. The provisioning token in HKLM\SOFTWARE\EmpVerify is kept unless
  -RemoveProvisioning is given, so a reinstall self-registers again. On a managed
  fleet, removal is normally driven by Intune, not this script.
#>
[CmdletBinding()]
param(
    [string]$PackageName = "EmpVerify.Agent",
    [switch]$RemoveProvisioning
)

$ErrorActionPreference = "Stop"

$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object System.Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "Administrator rights are needed - requesting elevation..."
    $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-NoExit", "-File", ('"{0}"' -f $PSCommandPath),
                 "-PackageName", ('"{0}"' -f $PackageName))
    if ($RemoveProvisioning) { $argList += "-RemoveProvisioning" }
    Start-Process powershell.exe -Verb RunAs -ArgumentList ($argList -join " ")
    return
}

Get-Process -Name "EmpVerify.Agent.Windows" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Remove-ItemProperty -Path "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run" -Name "EmpVerifyAgent" -ErrorAction SilentlyContinue

$prov = Get-AppxProvisionedPackage -Online | Where-Object DisplayName -eq $PackageName
if ($prov) {
    Write-Host "Removing provisioned package..."
    Remove-AppxProvisionedPackage -Online -PackageName $prov.PackageName | Out-Null
}

$pkg = Get-AppxPackage -Name $PackageName
if ($pkg) {
    Write-Host "Removing installed package for current user..."
    Remove-AppxPackage -Package $pkg.PackageFullName
}

if ($RemoveProvisioning) {
    Remove-Item -Path "HKLM:\SOFTWARE\EmpVerify" -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Removed HKLM\SOFTWARE\EmpVerify."
}

Write-Host "Done."
