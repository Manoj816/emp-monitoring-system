<#
.SYNOPSIS
  Removes the EmpVerify agent (admin offboarding / test cleanup).

.DESCRIPTION
  Must run elevated. Removes the provisioned package (so it is not reinstalled for
  new users) and the installed package for the current user. On a managed fleet,
  removal is normally driven by Intune, not this script.
#>
[CmdletBinding()]
param([string]$PackageName = "EmpVerify.Agent")

$ErrorActionPreference = "Stop"

$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object System.Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run this elevated (Administrator)."
}

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

Write-Host "Done."
