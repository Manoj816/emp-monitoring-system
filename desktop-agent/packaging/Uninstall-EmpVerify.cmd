@echo off
rem Double-click to remove the EmpVerify agent from this PC (asks for administrator approval).
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Uninstall.ps1" %*
if errorlevel 1 pause
