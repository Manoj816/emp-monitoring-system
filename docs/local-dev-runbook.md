# Local Development Runbook

How to run the full EmpVerify stack on a dev machine and perform a verification
end to end. Written for this project's dev laptops; ports and paths reflect the
known quirks (5432/8080 are occupied by unrelated containers).

## Machine facts

| Thing | Where |
|---|---|
| .NET 10 SDK | `%LOCALAPPDATA%\Microsoft\dotnet` (user-local, NOT on PATH — use `run-agent.cmd`) |
| JDK 21 | `%USERPROFILE%\.jdks\jdk-21.0.12.1+1` (set `JAVA_HOME` before Maven) |
| PostgreSQL (dev) | Docker, host port **5433** |
| Backend API | **http://localhost:8082** |
| Dev admin key | `X-Admin-Key: dev-admin-key` (dev only; Cognito replaces it) |

## 1. Database

Docker Desktop must be running, then:

```powershell
cd "C:\EMP Monitoring System"
docker compose up -d
```

## 2. Backend (keep the window open)

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-21.0.12.1+1"
cd "C:\EMP Monitoring System\backend"
.\mvnw.cmd spring-boot:run
```

Ready when `Started EmpVerifyBackendApplication` appears.
Health check: http://localhost:8082/actuator/health

## 3. Desktop agent

Run `run-agent.cmd` in the repo root (double-click works). A previously
registered device auto-connects (credentials are DPAPI-encrypted under
`%LOCALAPPDATA%\EmpVerify`); otherwise register with an enrollment token.

## 4. Admin operations (PowerShell)

```powershell
$h = @{ "X-Admin-Key" = "dev-admin-key" }

# List employees
$emps = Invoke-RestMethod "http://localhost:8082/api/v1/admin/employees" -Headers $h

# Create an employee — the ID/photo is mandatory, so it is one multipart request
# (multipart needs curl.exe on PS 5.1)
$emp = curl.exe -s -X POST "http://localhost:8082/api/v1/admin/employees" -H "X-Admin-Key: dev-admin-key" `
  -F "employeeCode=E-0002" -F "fullName=Full Name" -F "email=person@example.com" -F "department=IT" `
  -F "image=@C:\path\to\photo.jpg;type=image/jpeg" | ConvertFrom-Json

# Replace the reference photo later (resets the live enrollment)
curl.exe -X POST "http://localhost:8082/api/v1/admin/employees/$($emp.id)/reference-image" -H "X-Admin-Key: dev-admin-key" -F "image=@C:\path\to\photo.jpg;type=image/jpeg"

# Issue a one-time enrollment token (single use, 24h expiry)
$tok = Invoke-RestMethod -Method Post "http://localhost:8082/api/v1/admin/employees/$($emp.id)/enrollment-tokens" -Headers $h
$tok.enrollmentToken   # paste into the agent's registration screen

# Recent verification checks (audit feed)
Invoke-RestMethod "http://localhost:8082/api/v1/admin/checks" -Headers $h |
  Format-Table status, similarityScore, mismatchFlag, completedAt
```

## 5. Verify

In the agent, click **Verify now** (or wait for a scheduled prompt — up to 4
random times inside the 9:00–17:00 policy window). One webcam frame uploads;
the server decides and the activity log shows the verdict.

With the default **stub verifier** every capture returns `MATCH 95.0` — the
test proves the pipeline, not real matching. Real matching:
`EMPVERIFY_VERIFIER=rekognition` + AWS credentials (and re-upload real
reference photos).

## Stop / reset

- Backend: `Ctrl+C`. Database: `docker compose stop` (keeps data);
  `docker compose down -v` wipes it.
- Forget this device's registration: close the agent, delete
  `%LOCALAPPDATA%\EmpVerify`, register again with a fresh token.

## Troubleshooting

- **`dotnet run` says "No .NET SDKs were found"** — the PATH `dotnet` is a
  runtime-only install. Use `run-agent.cmd`, or call
  `%LOCALAPPDATA%\Microsoft\dotnet\dotnet.exe` directly.
- **Backend can't reach the DB** — confirm `docker compose ps` shows the db up
  and that nothing changed the 5433 mapping; the JDBC default uses `127.0.0.1`
  on purpose (a WSL relay intercepts `localhost`/`::1`).
- **Port 8082 busy** — set `SERVER_PORT` before starting the backend and update
  the agent's Backend URL field to match.
