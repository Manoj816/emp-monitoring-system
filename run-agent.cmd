@echo off
rem Launches the EmpVerify desktop agent.
rem Prefers the self-contained publish output (no .NET SDK needed); falls back
rem to `dotnet run` using the user-local SDK in %LOCALAPPDATA%\Microsoft\dotnet.

set PUBLISHED=%~dp0desktop-agent\publish\EmpVerify.Agent.Windows.exe
if exist "%PUBLISHED%" (
    start "" "%PUBLISHED%"
    exit /b 0
)

set LOCAL_DOTNET=%LOCALAPPDATA%\Microsoft\dotnet\dotnet.exe
if exist "%LOCAL_DOTNET%" (
    "%LOCAL_DOTNET%" run --project "%~dp0desktop-agent\src\EmpVerify.Agent.Windows"
    exit /b %errorlevel%
)

echo No published agent and no user-local .NET SDK found.
echo Build one first:  dotnet publish desktop-agent\src\EmpVerify.Agent.Windows -c Release -r win-x64 --self-contained -o desktop-agent\publish
exit /b 1
