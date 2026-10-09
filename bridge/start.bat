@echo off
setlocal EnableExtensions
rem Use the same checked launcher as the COM7 shortcut; default port is COM7.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
exit /b %ERRORLEVEL%
