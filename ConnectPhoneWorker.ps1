param([switch]$Stop)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$ports = @(8000, 8001, 10095)
$adbCandidates = @(
    (Join-Path $root 'tools\platform-tools\adb.exe'),
    (Join-Path (Split-Path -Parent (Split-Path -Parent $root)) 'work\android-sdk\platform-tools\adb.exe'),
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
)
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$adb = $adbCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $adb -and $adbCommand) { $adb = $adbCommand.Source }
if (-not $adb) { throw '找不到 adb。请安装 Android SDK Platform-Tools，或把 adb.exe 放入 tools\platform-tools。' }

function Invoke-Adb([string[]]$Arguments) {
    & $script:adb @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB 命令失败：adb $($Arguments -join ' ')" }
}

function Get-ConnectedDevices {
    $lines = & $script:adb devices -l
    if ($LASTEXITCODE -ne 0) { throw '无法读取 ADB 设备列表。' }
    $devices = @()
    foreach ($line in ($lines | Select-Object -Skip 1)) {
        if ($line -match '^(\S+)\s+device(?:\s|$)') { $devices += $Matches[1] }
    }
    $devices
}

function Test-HostServices {
    $face = Test-NetConnection 127.0.0.1 -Port 8000 -InformationLevel Quiet -WarningAction SilentlyContinue
    $overlap = Test-NetConnection 127.0.0.1 -Port 8001 -InformationLevel Quiet -WarningAction SilentlyContinue
    $speech = Test-NetConnection 127.0.0.1 -Port 10095 -InformationLevel Quiet -WarningAction SilentlyContinue
    $face -and $overlap -and $speech
}

if ($Stop) {
    foreach ($serial in (Get-ConnectedDevices)) {
        foreach ($port in $ports) { Invoke-Adb @('-s', $serial, 'reverse', '--remove', "tcp:$port") }
    }
    & (Join-Path $root 'server\stop_local_services.ps1')
    Write-Output '已停止电脑服务并撤销手机 USB 端口转发。'
    exit 0
}

$deviceOutput = & $adb devices -l
if ($LASTEXITCODE -ne 0) { throw '无法读取 ADB 设备列表。' }
$devices = Get-ConnectedDevices
if ($devices.Count -eq 0) {
    $unauthorized = $deviceOutput | Where-Object { $_ -match '\bunauthorized\b' }
    if ($unauthorized) { throw '手机已连接但尚未授权：解锁手机并确认“允许 USB 调试”。' }
    throw '未发现手机：用支持数据传输的 USB 线连接电脑，并在手机设置中开启 USB 调试。'
}
Write-Output "发现 $($devices.Count) 台已授权 Android 设备：$($devices -join ', ')"

if (-not (Test-HostServices)) {
    $listeners = Get-NetTCPConnection -State Listen -LocalPort $ports -ErrorAction SilentlyContinue
    if ($listeners) { throw '电脑端服务端口有未就绪的监听进程。请先运行 server\stop_local_services.ps1 后重试。' }
    Write-Output '正在启动人脸 MEC、FunASR 与双人分离服务；首次启动会加载模型。'
    & (Join-Path $root 'server\start_local_services.ps1')
    if (-not (Test-HostServices)) { throw '电脑端服务未全部就绪，请查看 server\artifacts\logs。' }
} else {
    Write-Output '电脑端三项服务均已就绪。'
}

foreach ($serial in $devices) {
    foreach ($port in $ports) { Invoke-Adb @('-s', $serial, 'reverse', "tcp:$port", "tcp:$port") }
    $qemu = (& $adb -s $serial shell getprop ro.kernel.qemu | Out-String).Trim()
    $isEmulator = $serial.StartsWith('emulator-') -or $qemu -eq '1'
    $apkName = if ($isEmulator) { 'LiveFaceTranscript-AVD-x86_64-final.apk' } else { 'LiveFaceTranscript-VIVO-X200-Pro-arm64.apk' }
    $apk = Join-Path $root "artifacts\$apkName"
    if (-not (Test-Path -LiteralPath $apk)) {
        $abi = if ($isEmulator) { 'x86_64' } else { 'arm64-v8a' }
        Write-Output "没有预构建安装包，正在为 $abi 生成 APK。"
        & (Join-Path $root 'gradlew.bat') ':app:assembleDebug' "-PtargetAbis=$abi"
        if ($LASTEXITCODE -ne 0) { throw 'APK 构建失败；请查看 Gradle 输出。' }
        $apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    }
    Write-Output "正在通过 USB 转发 8000/8001/10095，并安装 $apkName 到 $serial。"
    Invoke-Adb @('-s', $serial, 'install', '-r', $apk)
    Invoke-Adb @('-s', $serial, 'shell', 'am', 'force-stop', 'edu.scut.livetranscript')
    Invoke-Adb @('-s', $serial, 'shell', 'am', 'start', '-n', 'edu.scut.livetranscript/.MainActivity', '--ez', 'connectComputer', 'true')
    Write-Output "$serial 已完成服务转发、安装与应用启动。"
}

Write-Output '连接完成。USB 端口转发不依赖手机与电脑处于同一 Wi-Fi。'
