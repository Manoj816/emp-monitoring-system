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

```sh
docker compose up -d                 # PostgreSQL on :5432
cd backend && ./mvnw spring-boot:run # API on :8080 (JDK 21)
cd admin-portal && npm run dev       # portal on :5173, proxies /api -> :8080
cd desktop-agent && dotnet build     # agent (.NET 10 SDK)
```

## CI/CD

GitHub Actions (`.github/workflows/`): backend build/test + ECS deploy via OIDC,
agent build/test on windows-latest, web builds. Deploy jobs activate when the
repository variables listed in `infra/README.md` are set.

## Status

Phase 1 scaffold. Service implementations (`NOT_IMPLEMENTED` endpoints), the
agent UI flow, and the AWS infrastructure modules are the next work items.
