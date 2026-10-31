# Installing the EmpVerify agent on an employee's PC

This folder (or the `EmpVerify-Agent-Setup_<version>.zip`) is everything the
target PC needs. Nothing else has to be installed first.

## Requirements on the target PC

| | |
|---|---|
| Windows | Windows 10 version 1809 (build 17763) or newer, or Windows 11. Home and Pro both work. |
| Processor | 64-bit (x64). ARM64 PCs work on Windows 11 only. |
| Account | An administrator approves the install once (UAC prompt). Employees can be standard users. |
| Internet | To reach the EmpVerify backend, and once to fetch the Microsoft WebView2 runtime if the PC lacks it. |
| Camera | A webcam the employee can use for the face scan. |

The package carries its own .NET runtime (self-contained), so no .NET install is
needed. The installer adds the WebView2 runtime (part of Microsoft Edge; already
present on Windows 11 and most Windows 10 PCs) when it is missing.

## Steps

1. Copy the bundle to the PC and unzip it to a folder (for example `C:\EmpVerify-Setup`).
2. Rename `provisioning.sample.json` to `provisioning.json` and paste the tenant
   **provisioning token** into it. Keep `backendUrl` as the EmpVerify server address.
   Without this file the agent cannot set itself up and says so in a tray notification.
3. Double-click **`Install-EmpVerify.cmd`** and accept the administrator prompt.
   A window shows each step; it stays open so you can read the result.
4. The agent starts in the background and registers this PC as **Unassigned**. It
   never opens a window of its own — there is only a tray icon and a short
   notification; double-click the tray icon to see its activity log.
5. In the admin portal, open **Devices**, find the PC by its hostname and **Assign** it
   to the employee. From then on the agent runs the daily check at a random time
   inside the work window; the first check is the enrollment scan.

The agent runs in the background every day and comes back after every restart:
it starts at sign-in through the package's startup task (Settings > Apps > Startup
shows it) and through a machine-wide run entry the installer adds, which a standard
user cannot switch off. Only one copy runs at a time. A standard user cannot
uninstall a per-machine (provisioned) package.

## Command-line use (PowerShell, elevated or not — it elevates itself)

```powershell
.\Install-PerMachine.ps1                                   # picks up files next to the script
.\Install-PerMachine.ps1 -ProvisioningToken (Read-Host) -BackendUrl http://<server>
.\Install-PerMachine.ps1 -SkipWebView2 -NoLaunch           # offline image build, start at next sign-in
.\Uninstall.ps1                                            # remove (keeps the provisioning token)
.\Uninstall.ps1 -RemoveProvisioning                        # remove everything
```

## Upgrading

Run the same installer with a newer package: the version in the file name must
be higher than the installed one. Settings and the device identity are kept, so
the PC stays assigned to its employee.

## If something goes wrong

- **"Windows 10 version 1809 ... is required"** — update Windows, or use a newer PC.
- **"The package is 64-bit"** — the PC runs 32-bit Windows; it is not supported.
- **"Could not download/install WebView2"** — the PC is offline. Install the
  WebView2 runtime from Microsoft (Evergreen Standalone Installer) and run the
  installer again. Until then, the check opens in the default browser.
- **The agent shows "Unassigned" forever** — the PC is not assigned yet, or the
  backend URL is wrong. Check Devices in the admin portal and `backendUrl`.
- **Package signing error on install** — the dev package is signed by a test
  certificate; keep `empverify-dev.cer` next to the installer. Production
  packages are signed by a public CA and need no certificate file.
