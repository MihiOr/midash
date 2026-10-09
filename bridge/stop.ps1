$ErrorActionPreference = 'Stop'
$relayPath = Join-Path $PSScriptRoot 'com_relay.py'
$listeners = Get-NetTCPConnection -LocalPort 8766 -State Listen -ErrorAction SilentlyContinue
foreach ($listener in $listeners) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if (!$process -or !$process.CommandLine.Contains($relayPath)) {
        throw 'Port 8766 belongs to another application; it was not stopped.'
    }
    Stop-Process -Id $process.ProcessId
    Write-Host 'MiNini relay stopped; COM port released.'
}
if (!$listeners) { Write-Host 'MiNini relay is not running.' }
