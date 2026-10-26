<#
.SYNOPSIS
  Installs the EmpVerify agent for ALL users on this machine (per-machine).

.DESCRIPTION
  Must run elevated (Administrator). Provisions the MSIX so it is installed for
  every user account on the device, including future ones — a standard
  (non-admin) user cannot remove a provisioned package. This is the enforceable
  "employee cannot uninstall it" path, and it is what Intune/MDM does under the
  hood when it deploys the package to a managed fleet.

  For a dev (self-signed) package, the signing certificate must be trusted first;
  pass -TrustCert to import the exported .cer into LocalMachine\TrustedPeople.
  Production packages signed by a publicly-trusted CA do not need this.

.EXAMPLE
  # Elevated PowerShell:
  ./Install-PerMachine.ps1 -MsixPath .\out\EmpVerify.Agent_0.1.0.0_x64.msix -TrustCert .\out\empverify-dev.cer
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$MsixPath,
    [string]$TrustCert
)

$ErrorActionPreference = "Stop"

$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object System.Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run this elevated (Administrator). Per-machine provisioning requires admin."
}

if ($TrustCert) {
    Write-Host "Importing signing certificate into LocalMachine\TrustedPeople..."
    Import-Certificate -FilePath $TrustCert -CertStoreLocation Cert:\LocalMachine\TrustedPeople | Out-Null
}

Write-Host "Provisioning $MsixPath for all users..."
# Provisioned packages install for every current and future user on the device.
Add-AppxProvisionedPackage -Online -PackagePath $MsixPath -SkipLicense | Out-Null

Write-Host "Installing for the current user session as well..."
Add-AppxPackage -Path $MsixPath -ForceApplicationShutdown

Write-Host "Done. The agent is provisioned machine-wide."
Write-Host "Managed fleets: deploy the same .msix via Intune (line-of-business app) for central control and auto-update."
