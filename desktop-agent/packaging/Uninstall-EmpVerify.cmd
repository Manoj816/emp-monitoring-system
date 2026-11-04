@echo off
rem Double-click to remove the EmpVerify agent from this PC (asks for administrator approval).
setlocal

if not exist "%~dp0Uninstall.ps1" (
    echo.
    echo   Uninstall.ps1 is not next to this file, so there is nothing to run.
    echo   Extract the whole zip first ^(right-click the zip, Extract All^) and
    echo   run this from the extracted folder.
    echo.
    pause
    exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Uninstall.ps1" %*
echo.
pause
