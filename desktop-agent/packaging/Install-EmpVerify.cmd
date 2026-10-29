@echo off
rem Double-click to install the EmpVerify agent on this PC for all users.
rem Asks for administrator approval (UAC) and keeps the window open so you can read the result.
rem Picks up the .msix, the signing certificate and provisioning.json from this folder.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-PerMachine.ps1" %*
if errorlevel 1 pause
