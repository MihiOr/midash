@echo off
rem Compatibility for existing shortcuts: this launcher now starts COM7.
call "%~dp0start_COM7_forwarding.bat" %*
exit /b %ERRORLEVEL%
