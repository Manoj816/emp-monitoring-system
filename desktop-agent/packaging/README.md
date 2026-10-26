# EmpVerify Agent — MSIX Packaging

Per-machine MSIX installer for the Windows desktop agent.

## Why MSIX (per-machine)

- **Clean install / upgrade / uninstall** and an auto-update path (App Installer / Intune).
- Declaring the **`webcam`** capability makes the OS camera-in-use indicator work
  and lists the app under Settings → Privacy → Camera — the transparency the
  product requires.
- **Provisioned (per-machine)** deployment installs the agent for every user on
  the device; a standard (non-admin) user cannot remove a provisioned package.
  This is the enforceable "employee can't uninstall it" path, and it is what
  Intune does under the hood. The guarantee holds only when employees are
  **standard users** on **company-managed** machines — a local admin can remove
  anything.

## Files

| File | Purpose |
|---|---|
| `AppxManifest.xml` | Package manifest (identity, webcam + runFullTrust capabilities, entry point) |
| `Images/` | Tile/logo PNGs (regenerate with `generate-logos.ps1`) |
| `build-msix.ps1` | Publish → stage → pack → sign; produces `out/*.msix` |
| `Install-PerMachine.ps1` | Elevated: provisions the package for all users |
| `Uninstall.ps1` | Elevated: removes provisioned + installed package |

## Build

```powershell
# Dev (self-signed, for local testing):
./build-msix.ps1

# Production (real code-signing certificate; subject MUST match the manifest Publisher):
./build-msix.ps1 -PfxPath C:\certs\empverify.pfx -PfxPassword (Read-Host -AsSecureString)
```

Requires the Windows SDK tools `makeappx.exe` and `signtool.exe`. The script
auto-discovers them from an installed Windows SDK; for machines without the SDK,
the NuGet package `Microsoft.Windows.SDK.BuildTools` carries both (this is how
CI builds the package — see `.github/workflows/agent.yml`). Building and signing
do **not** require admin; installing per-machine does.

## Install (per-machine, elevated)

```powershell
# Dev package: trust the self-signed cert, then provision for all users.
./Install-PerMachine.ps1 -MsixPath .\out\EmpVerify.Agent_0.1.0.0_x64.msix -TrustCert .\out\empverify-dev.cer

# Production package (publicly-trusted cert): no -TrustCert needed.
./Install-PerMachine.ps1 -MsixPath .\out\EmpVerify.Agent_<version>_x64.msix
```

## Managed fleet (recommended for production)

Deploy the **same signed `.msix`** through **Microsoft Intune** as a line-of-business
app, targeted at device groups. Intune handles per-machine install, auto-update
on version bump, and central removal — and because managed employees are standard
users, they cannot uninstall it. No separate build is needed; it's the identical
package this script produces, signed with a production certificate.

## Production prerequisites

1. **Code-signing certificate** (Azure Trusted Signing or an EV/OV cert). Update
   the manifest `Publisher` to the certificate subject before packing.
2. Store the signing cert as a CI secret (base64), never in the repo — see the
   commented production-signing steps in `.github/workflows/agent.yml`.
3. Bump `Version` in `AppxManifest.xml` (or pass `-Version`) for each release so
   upgrades are recognized.
