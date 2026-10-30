@echo off
rem Starts the two web apps the demo needs on this laptop (they are not hosted in AWS yet):
rem   admin portal  -> http://localhost:5176   (sign in with the Cognito admin user)
rem   liveness page -> http://localhost:5175   (opened by the agent inside its check window)
rem The backend, database, images and face recognition run in AWS and need nothing started here.
rem Close the two windows this opens to stop them.
setlocal
set ROOT=%~dp0..
start "EmpVerify admin portal (localhost:5176)" cmd /k "cd /d "%ROOT%\admin-portal" && npm run dev"
start "EmpVerify liveness page (localhost:5175)" cmd /k "cd /d "%ROOT%\liveness-ui" && npm run dev"
timeout /t 6 /nobreak >nul
start "" http://localhost:5176/
