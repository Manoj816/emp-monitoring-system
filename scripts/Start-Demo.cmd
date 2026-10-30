@echo off
rem Starts the two web apps the demo needs on this laptop (they are not hosted in AWS yet):
rem   admin portal  -> http://localhost:5176   (sign in with the Cognito admin user)
rem   liveness page -> http://localhost:5175   (opened by the agent inside its check window)
rem The backend, database, images and face recognition run in AWS and need nothing started here.
rem A server whose port is already in use is skipped (it is already running). Close the
rem windows this opens to stop the servers.
setlocal
set ROOT=%~dp0..

netstat -ano | findstr /r /c:":5176 .*LISTENING" >nul
if errorlevel 1 (
  start "EmpVerify admin portal (localhost:5176)" cmd /k "cd /d "%ROOT%\admin-portal" && npm run dev"
) else (
  echo Admin portal already running on port 5176 - not starting a second one.
)

netstat -ano | findstr /r /c:":5175 .*LISTENING" >nul
if errorlevel 1 (
  start "EmpVerify liveness page (localhost:5175)" cmd /k "cd /d "%ROOT%\liveness-ui" && npm run dev"
) else (
  echo Liveness page already running on port 5175 - not starting a second one.
)

timeout /t 6 /nobreak >nul
start "" http://localhost:5176/
