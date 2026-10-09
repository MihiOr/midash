param(
    [ValidatePattern('^COM[0-9]+$')][string]$Port = 'COM7',
    [switch]$Foreground
)
$ErrorActionPreference = 'Stop'
$relayPath = Join-Path $PSScriptRoot 'com_relay.py'

# Repeated launches reuse the existing relay instead of competing for the serial port.
$listener = Get-NetTCPConnection -LocalPort 8766 -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $client.Connect('127.0.0.1', 8766)
        $stream = $client.GetStream()
        $stream.ReadTimeout = 1500
        $reader = [System.IO.StreamReader]::new($stream)
        $info = $reader.ReadLine() | ConvertFrom-Json
        if ($info.protocol -ne "minini-binary-v1") { throw "Old relay is running. Run bridge/stop.ps1, then start again." }
        if ($info.relayPort -ne $Port) { throw "Port 8766 already serves $($info.relayPort). Run bridge/stop.ps1 before changing COM ports." }
        Write-Host "Relay already running for $Port (COM available: $($info.connected))."
        return
    } finally { $client.Dispose() }
}

$python = Join-Path $PSScriptRoot '.venv/Scripts/python.exe'
if (!(Test-Path -LiteralPath $python)) {
    python -m venv (Join-Path $PSScriptRoot '.venv')
    if ($LASTEXITCODE -ne 0) { throw 'Could not create Python environment' }
}
& $python -c 'import serial'
if ($LASTEXITCODE -ne 0) {
    & $python -m pip install -r (Join-Path $PSScriptRoot 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Could not install pyserial' }
}
if ($Foreground) {
    & $python $relayPath --port $Port
    exit $LASTEXITCODE
}

$logDir = Join-Path $PSScriptRoot '.runtime'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$process = Start-Process -FilePath $python -ArgumentList @('-u', ('"' + $relayPath + '"'), '--port', $Port) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDir 'stdout.log') -RedirectStandardError (Join-Path $logDir 'relay.log') -PassThru
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    Start-Sleep -Milliseconds 100
    $process.Refresh()
    if ($process.HasExited) { throw "Relay exited. See $logDir/relay.log" }
    if (Get-NetTCPConnection -LocalPort 8766 -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "$Port relay is running in the background. It reconnects automatically after unplugging."
        Write-Host "Log: $logDir/relay.log"
        Write-Host 'Stop and release the COM port: .\bridge\stop.ps1'
        return
    }
}
throw "Relay did not start listening. See $logDir/relay.log"
