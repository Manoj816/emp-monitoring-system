@echo off
rem Double-click to install the EmpVerify agent on this PC for all users.
rem Asks for administrator approval (UAC) and keeps the window open so you can read the result.
rem Picks up the .msix, the signing certificate and provisioning.json from this folder.
setlocal

rem Double-clicked while still inside the .zip, Windows copies this one file to a
rem temporary folder and runs it there, leaving the package and the install
rem script behind. Say so, instead of flashing a window that closes unread.
if not exist "%~dp0Install-PerMachine.ps1" (
    echo.
    echo   The EmpVerify installer cannot run from inside the zip.
    echo.
    echo   Windows copied only Install-EmpVerify.cmd to a temporary folder:
    echo     %~dp0
    echo   The package and the install script were left behind, so there is
    echo   nothing here to install.
    echo.
    echo   Right-click the zip, choose Extract All, then open the extracted
    echo   folder and run Install-EmpVerify.cmd from there.
    echo.
    pause
    exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-PerMachine.ps1" %*
echo.
pause
