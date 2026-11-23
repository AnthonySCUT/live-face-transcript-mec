$ErrorActionPreference = 'Stop'
$logDir = Join-Path $PSScriptRoot 'artifacts\logs'
$services = @(
    @{ Name = 'overlap'; Script = 'server/overlap_server.py'; Port = 8001 },
    @{ Name = 'funasr'; Script = 'server/funasr_wss_server.py'; Port = 10095 },
    @{ Name = 'face-mec'; Script = 'server/server.py'; Port = 8000 }
)
$allProcesses = @(Get-CimInstance Win32_Process)
foreach ($service in $services) {
    $pidFile = Join-Path $logDir "$($service.Name).pid"
    $processIds = @()
    if (Test-Path $pidFile) {
        $processIds += [int](Get-Content $pidFile -Raw)
    }
    $processIds += Get-NetTCPConnection -State Listen -LocalPort $service.Port -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess
    $treeIds = [System.Collections.Generic.List[int]]::new()
    foreach ($processId in ($processIds | Sort-Object -Unique)) { $treeIds.Add([int]$processId) }
    for ($index = 0; $index -lt $treeIds.Count; $index++) {
        $parentId = $treeIds[$index]
        foreach ($child in $allProcesses | Where-Object { $_.ParentProcessId -eq $parentId }) {
            if (-not $treeIds.Contains([int]$child.ProcessId)) { $treeIds.Add([int]$child.ProcessId) }
        }
    }
    for ($index = $treeIds.Count - 1; $index -ge 0; $index--) {
        $processId = $treeIds[$index]
        $process = $allProcesses | Where-Object { $_.ProcessId -eq $processId } | Select-Object -First 1
        if ($process -and $process.CommandLine -like "*$($service.Script)*") {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        }
    }
    if (Test-Path $pidFile) { Remove-Item -LiteralPath $pidFile }
}
Write-Output 'Local face MEC, FunASR, and overlap services stopped.'
