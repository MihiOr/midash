@echo off
setlocal EnableExtensions
rem Windows launcher kept beside assets for convenient double-click startup.
rem The actual relay and its Python environment belong to the project bridge folder.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\..\..\..\bridge\start.ps1" -Port COM7 %*
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" (
    echo Could not start COM7 forwarding. See the error above.
    pause
)
exit /b %RESULT%
