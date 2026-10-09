@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT=%~dp0"
set "RELAY=%ROOT%com_relay.py"
set "FOUND=0"

for /f "tokens=5" %%P in ('netstat -ano -p tcp ^| findstr /R /C:":8766 .*LISTENING"') do (
    set "FOUND=1"
    set "PID=%%P"

    rem Verify that the process listening on 8766 is our com_relay.py.
    set "CMDLINE="
    for /f "usebackq delims=" %%C in (`powershell.exe -NoProfile -Command "$p=Get-CimInstance Win32_Process -Filter 'ProcessId=!PID!'; if($p){$p.CommandLine}"`) do set "CMDLINE=%%C"

    echo !CMDLINE! | findstr /I /L /C:"%RELAY%" >nul
    if errorlevel 1 (
        echo Port 8766 belongs to another application; it was not stopped.
        exit /b 1
    )

    taskkill /PID !PID! /F >nul
    if errorlevel 1 (
        echo Could not stop MiNini relay.
        exit /b 1
    )
    echo MiNini relay stopped; COM port released.
)

if "%FOUND%"=="0" echo MiNini relay is not running.
