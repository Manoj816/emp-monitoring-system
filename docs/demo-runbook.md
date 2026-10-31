# EmpVerify — running the demo end to end (from scratch)

## 1. What runs where

| Piece | Where it runs | Do you start it? |
|---|---|---|
| Backend API (Spring Boot) | AWS ECS Fargate, `empverify-cluster` | No — always on |
| Database (PostgreSQL) | AWS RDS `empverify-db` | No — always on |
| Reference photos and evidence frames | AWS S3 `empverify-images-…` | No |
| Admin sign-in and the liveness page's guest access | AWS Cognito (user pool + identity pool) | No |
| Face comparison and live-person check | AWS Rekognition | No |
| **Admin portal** (React) | this laptop, dev server on http://localhost:5176 | **Yes** — not hosted yet |
| **Liveness page** (React, shown inside the agent's check window) | this laptop, dev server on http://localhost:5175 | **Yes** — not hosted yet |
| **Desktop agent** (tray app) | every employee PC — for the demo, this laptop | Installed once; starts itself at every sign-in |
| Docker / local PostgreSQL / local backend | only for development | No — not used by the demo |

So for a demo on this laptop you need exactly three things running: the two
dev servers (one double-click, see step 4) and the installed agent (starts by
itself). Everything else is in AWS and needs nothing from you.

Local tooling that must already be installed on this laptop (it is): Node.js
(for the two dev servers), the AWS CLI with the `empverify` profile (only for
deploys, not for the demo).

## 2. Remove the current agent (start from scratch)

1. Right-click the EmpVerify tray icon → **Exit**.
2. Open `C:\EMP Monitoring System\desktop-agent\packaging\out\setup-0.13.0.0`
   and double-click **`Uninstall-EmpVerify.cmd`** (accept the administrator prompt).
   This removes the app for all users, the run-at-sign-in entry, and keeps the
   provisioning token so the next install registers again without typing anything.
3. Nothing else to clean up: every run of the installer makes the agent discard
   its stored device identity and register the system again, so it comes back
   as **Unassigned** in the Devices tab (same row, history kept), you assign the
   employee again, and the first check after that is the enrollment scan.

## 3. Reset the data in the portal (optional)

In the admin portal, **Reset demo data** wipes every employee, device, photo and
check. Use it when you want to demonstrate the whole flow from an empty table.
Skip it if you want to keep the existing employee and history.

## 4. Start the two web apps (one double-click)

Double-click **`C:\EMP Monitoring System\scripts\Start-Demo.cmd`**. It opens two
console windows (admin portal on 5176, liveness page on 5175) and then the
portal in your browser. Leave both windows open for the whole demo; closing
them stops the servers.

## 5. Install the agent (one double-click)

1. Open `C:\EMP Monitoring System\desktop-agent\packaging\out\setup-0.13.0.0`.
   It already contains `provisioning.json` (backend address + tenant token), so
   nothing has to be typed.
2. Double-click **`Install-EmpVerify.cmd`** and accept the administrator prompt.
   A window shows the six steps (PC check, certificate, WebView2 runtime,
   provisioning, package install, launch) and stays open so you can read it.
3. The agent starts in the background: no window opens, only a tray icon and a
   short notification. It registers this PC as **Unassigned** on its own.
   Double-click the tray icon to see its activity log.

To install on another PC: copy the whole `setup-0.13.0.0` folder there (or the
zip next to it plus a `provisioning.json`) and do the same two clicks. The
other PC needs Windows 10 1809+ / Windows 11, 64-bit, internet access, and a
webcam. Nothing else. Note: until the two web apps are hosted in AWS, checks
only work on the laptop that runs them (step 4).

## 6. The demo flow in the portal

1. Sign in at http://localhost:5176 with the Cognito admin user.
2. **Employees → + Add employee**: code, name, email, department and the ID/photo
   (upload or webcam). The photo is mandatory.
3. **Devices**: this PC shows as Unassigned → pick the employee → **Assign**.
   The agent notices within seconds; its log says the day's check is planned.
4. On the agent window click **Verify now**. The first check is the
   **enrollment scan**: a full-screen live face scan compared with the uploaded
   photo. On a match, the matched frame is stored as the second reference and
   Employees shows *Live enrollment: done* (see the **Details** button for both pictures).
5. Every later check is a live scan compared with both references. Use the
   agent's **Demo** panel to run one at an exact time, or **Liveness check** to run
   one now. Hold up a photo or cover the camera to see a flagged result.
6. **Verification checks**: tiles, filters (every column, including a date range)
   and **Review** on flagged rows with the three pictures and the reason.
   Missed checks show as MISSED with why the workstation could not answer — the
   screen was locked, the machine slept or was shutting down, the employee closed
   the window, or it was open with no response — and are retried once later the
   same day. A whole work day with no check at all (machine off) shows as NO CHECK.

## 7. Other PCs, daily running

- The agent starts at every sign-in (package startup task plus a machine-wide
  run entry) and plans one random check per working day; a restart does not
  need any action.
- Offboarding an employee deregisters their PC (back to Unassigned);
  **Reassign** on the Devices page hands a PC to another employee.
- Deploying backend changes is a developer task (`infra/iam/README.md` explains
  the access model); the demo never needs it.
