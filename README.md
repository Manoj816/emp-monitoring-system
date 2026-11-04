# EmpVerify — Remote Workforce Identity Verification System

Verifies that the person using a remote employee's laptop is the enrolled
employee, via transparent webcam-based identity checks against an approved
reference image. Independent of Teams/Zoom. Full design:
`Remote-Workforce-Identity-Verification-Proposal.pdf`.

## Repository layout

| Path | What | Stack |
|---|---|---|
| `backend/` | REST API: device registration, checks, verification, audit | Java 21, Spring Boot, PostgreSQL, Flyway |
| `desktop-agent/` | Windows agent (platform-neutral core + WPF head) | C# / .NET 10 |
| `desktop-agent/packaging/` | Per-machine MSIX installer (manifest, build/install scripts) | MSIX |
| `admin-portal/` | Admin dashboard: enrollment, review queue | React + TypeScript (Vite) |
| `liveness-ui/` | Face Liveness page opened from the agent (Phase 2) | React + Amplify UI |
| `infra/` | Terraform: OIDC bootstrap + AWS modules | Terraform |
| `docs/openapi/agent-api.yaml` | Agent↔backend contract (platform-neutral) | OpenAPI 3 |

## Design rules (do not regress)

- All verification decisions are **server-side**; the agent never computes or
  reports its own result.
- Two check paths: **passive** periodic captures (DetectFaces + CompareFaces) and
  **interactive** Face Liveness. Liveness cannot run silently; the agent must
  release the camera before the browser flow.
- The agent opens a scan at start-up only for a system that still owes its
  **enrollment scan** (fresh install, or a re-enrollment an admin asked for). An
  enrolled system starts silently — signing in is not a reason to scan somebody;
  the day's check comes at its random time.
- A scheduled check is **a debt due by the end of the work window**, not an
  instant. Coming due while the screen is locked records nothing: the agent holds
  the check and opens it shortly after the employee is back (after a short random
  delay, so it is not simply "the moment I unlock"). It becomes a miss only if the
  window closes first, and the record then states what was observed, never a guess.
- A slot the machine **slept through** is run late while the window is still open,
  and recorded as the day's miss once it has closed. A face scan is never opened
  outside the hours the employee is checked in.
- **Reinstalling the agent never costs a PC its employee.** The install id marks
  the machine, not the run; the installer keeps the registration it finds; and a
  machine that re-registers from scratch (a re-image, a lost profile) keeps the
  employee it was assigned to. It still gets a new secret and owes an enrollment
  scan, so the employee's own face is judged against the stored reference before
  that machine can produce an ordinary check. A revoked device is the exception
  and comes back unassigned.
- A check nobody answers can be **photographed once**, as the response window
  closes, and the frame kept with the missed check — so "the desk was empty" and
  "the employee ignored it" stop looking identical. It is off unless a deployment
  turns it on (`check_policy.capture_on_timeout`), the employee is told in the
  check window before the countdown starts, nothing is ever compared against the
  frame, and only a plain timeout is photographed: a locked or sleeping machine
  already says what happened, and nobody chose to be in front of that camera.
- Employees can be created **in bulk from a CSV**, with their ID/photos chosen
  alongside it and paired to the rows by file name. The file lands whole or not
  at all: one bad row imports nothing and every bad row is reported at once, so
  the portal never shows a half-imported list. A row may leave its photo for
  later — those employees are listed as "ID / photo: missing" and cannot be
  verified until one is uploaded, which is the honest state rather than a
  blocked import. The picture has to be a JPEG or PNG; a PDF of an ID card is a
  document, not something a face can be compared against.
- A record never asserts what the server cannot see. "No check was opened" and
  "the machine was off" are different claims: the agent signs in about every
  minute, so the last-contact time is what separates a machine nobody used from a
  machine that was in use with no agent running on it.
- Checks are **transparent**: visible prompt, OS camera indicator, no covert capture.
- `CHECK` is a human-review status, never an automatic verdict.
- Evidence is kept only for CHECK/flagged events; MATCH captures are discarded.
- Replay protection: server-issued one-time check ID + nonce, short expiry.
- Platform-neutral agent core (`EmpVerify.Agent.Core`) — macOS head reuses it.
- AWS access from CI is **OIDC role-based only**; no IAM user keys.

## Local development

Everyday commands run through the root `package.json` (Windows dev machines;
full details in `docs/local-dev-runbook.md`):

```sh
npm run db          # start PostgreSQL (:5433)        npm run db:stop / db:reset
npm run backend     # start the API (:8082)           backend:build / backend:test
npm run agent       # launch the desktop agent        agent:build / agent:test / agent:publish
npm run portal      # admin portal dev server (:5173) portal:install first time
npm run health      # backend health check
npm run employees   # list enrolled employees
npm run token       # issue an enrollment token       npm run token -- E-0002
npm run checks      # recent verification checks
```

Dev-machine note: if `dotnet`/`java` on PATH are the wrong versions, the
toolchains live at `%LOCALAPPDATA%\Microsoft\dotnet` (SDK 10) and
`%USERPROFILE%\.jdks\jdk-21.*` (set `JAVA_HOME` accordingly), or use
`run-agent.cmd`, which resolves this automatically.

Agent smoke test: issue an enrollment token
(`POST /api/v1/admin/employees/{id}/enrollment-tokens` with header
`X-Admin-Key: dev-admin-key`), paste it into the agent's registration screen,
then click "Verify now" for a real webcam capture verified by the backend.

## CI/CD

GitHub Actions (`.github/workflows/`): backend build/test + ECS deploy via OIDC,
agent build/test on windows-latest, web builds. Deploy jobs activate when the
repository variables listed in `infra/README.md` are set.

## Status

Phase 1 scaffold. Service implementations (`NOT_IMPLEMENTED` endpoints), the
agent UI flow, and the AWS infrastructure modules are the next work items.
