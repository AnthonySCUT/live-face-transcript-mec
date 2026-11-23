$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $PSScriptRoot 'artifacts\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$occupied = Get-NetTCPConnection -State Listen -LocalPort 8000, 8001, 10095 -ErrorAction SilentlyContinue
if ($occupied) {
    $ports = ($occupied | Select-Object -ExpandProperty LocalPort -Unique | Sort-Object) -join ', '
    throw "Service port(s) $ports already have a listener. Run server\stop_local_services.ps1, then start again."
}

$asrPython = Join-Path $PSScriptRoot '.venv-asr\Scripts\python.exe'
if (-not (Test-Path $asrPython)) { throw 'Create server\.venv-asr and install server\requirements-asr.txt plus CUDA-enabled PyTorch first.' }
$asrLog = Join-Path $logDir 'funasr.log'
$asrErr = Join-Path $logDir 'funasr-error.log'
$asr = Start-Process -FilePath $asrPython -WorkingDirectory $root -WindowStyle Hidden -PassThru `
    -ArgumentList @('-u', 'server/funasr_wss_server.py', '--host', '0.0.0.0', '--port', '10095', '--ngpu', '1', '--device', 'cuda') `
    -RedirectStandardOutput $asrLog -RedirectStandardError $asrErr
$asr.Id | Set-Content (Join-Path $logDir 'funasr.pid')

$facePython = Join-Path $PSScriptRoot '.venv-face\Scripts\python.exe'
if (-not (Test-Path $facePython)) { throw 'Create server\.venv-face and install server\requirements.txt first.' }
$faceLog = Join-Path $logDir 'face-mec.log'
$faceErr = Join-Path $logDir 'face-mec-error.log'
$face = Start-Process -FilePath $facePython -WorkingDirectory $root -WindowStyle Hidden -PassThru `
    -ArgumentList @('-u', 'server/server.py', '--host', '0.0.0.0', '--port', '8000') `
    -RedirectStandardOutput $faceLog -RedirectStandardError $faceErr
$face.Id | Set-Content (Join-Path $logDir 'face-mec.pid')

$asrReady = $false
for ($attempt = 0; $attempt -lt 240; $attempt++) {
    if ($asr.HasExited) { Get-Content $asrErr -Tail 40; throw 'FunASR exited during startup.' }
    $tcp = Test-NetConnection 127.0.0.1 -Port 10095 -InformationLevel Quiet
    if ($tcp) { $asrReady = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $asrReady) { throw 'FunASR did not open port 10095 within 240 seconds. Check server\artifacts\logs\funasr-error.log.' }

$overlapLog = Join-Path $logDir 'overlap.log'
$overlapErr = Join-Path $logDir 'overlap-error.log'
$overlap = Start-Process -FilePath $asrPython -WorkingDirectory $root -WindowStyle Hidden -PassThru `
    -ArgumentList @('-u', 'server/overlap_server.py', '--host', '0.0.0.0', '--port', '8001', '--asr-url', 'ws://127.0.0.1:10095', '--model-dir', 'server/artifacts/sepformer-whamr16k') `
    -RedirectStandardOutput $overlapLog -RedirectStandardError $overlapErr
$overlap.Id | Set-Content (Join-Path $logDir 'overlap.pid')

$overlapReady = $false
for ($attempt = 0; $attempt -lt 240; $attempt++) {
    if ($overlap.HasExited) { Get-Content $overlapErr -Tail 40; throw 'Overlap service exited during startup.' }
    $tcp = Test-NetConnection 127.0.0.1 -Port 8001 -InformationLevel Quiet
    if ($tcp) { $overlapReady = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $overlapReady) { throw 'Overlap service did not become ready within 240 seconds. Check server\artifacts\logs\overlap-error.log.' }

$health = Invoke-RestMethod 'http://127.0.0.1:8001/health' -TimeoutSec 5
$faceHealth = Invoke-RestMethod 'http://127.0.0.1:8000/health' -TimeoutSec 5
Write-Output "Face MEC: $($faceHealth.status) at http://127.0.0.1:8000 (PID $($face.Id))"
Write-Output "FunASR: TCP ready at ws://127.0.0.1:10095 (PID $($asr.Id))"
Write-Output "Overlap: $($health.status), device=$($health.device), http://127.0.0.1:8001 (PID $($overlap.Id))"
