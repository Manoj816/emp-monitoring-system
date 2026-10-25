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
| `admin-portal/` | Admin dashboard: enrollment, review queue | React + TypeScript (Vite) |
| `liveness-ui/` | Face Liveness page opened from the agent (Phase 2) | React + Amplify UI |
| `infra/` | Terraform: OIDC bootstrap + AWS modules | Terraform |
| `docs/openapi/agent-api.yaml` | Agent↔backend contract (platform-neutral) | OpenAPI 3 |

## Design rules (do not regress)

- All verification decisions are **server-side**; the agent never computes or
  reports its own result.
- Two check paths: **passive** periodic captures (DetectFaces + CompareFaces) and
  **interactive** Face Liveness at session start/escalation. Liveness cannot run
  silently; the agent must release the camera before the browser flow.
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
