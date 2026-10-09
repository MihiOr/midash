$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (!(Test-Path -LiteralPath '.venv/Scripts/python.exe')) {
    python -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the Python environment.' }
}
& .venv/Scripts/python.exe -c 'import PySide6, websocket' 2>$null
if ($LASTEXITCODE -ne 0) {
    & .venv/Scripts/python.exe -m pip install -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw 'Could not install simulator dependencies.' }
}
& .venv/Scripts/python.exe main.py @args
