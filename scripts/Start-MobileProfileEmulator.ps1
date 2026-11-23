$ErrorActionPreference = 'Stop'
$androidSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$emulator = Join-Path $androidSdk 'emulator\emulator.exe'
$adb = Join-Path $androidSdk 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $emulator)) { throw "找不到 Android Emulator：$emulator" }
if (-not (Test-Path -LiteralPath $adb)) { throw "找不到 ADB：$adb" }

$running = & $adb devices | Select-String '^emulator-\d+\s+device'
foreach ($line in $running) {
    $serial = ($line.ToString() -split '\s+')[0]
    & $adb -s $serial emu kill | Out-Null
}
if ($running) {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (-not (& $adb devices | Select-String '^emulator-\d+\s+device')) { break }
        Start-Sleep -Seconds 1
    }
}

$arguments = @(
    '@MEC_API_35',
    '-cores', '2',
    '-memory', '2048',
    '-lowram',
    '-gpu', 'software',
    '-no-snapshot-load',
    '-camera-back', 'webcam0',
    '-camera-front', 'none'
)
$process = Start-Process -FilePath $emulator -ArgumentList $arguments -PassThru
Write-Output "Started MEC_API_35 with 2 guest CPU cores, 2048 MB RAM, and software graphics rendering (PID $($process.Id))."

$serial = $null
for ($attempt = 0; $attempt -lt 180; $attempt++) {
    $deviceLine = & $adb devices | Select-String '^emulator-\d+\s+device'
    if ($deviceLine) { $serial = ($deviceLine[0].ToString() -split '\s+')[0]; break }
    Start-Sleep -Seconds 2
}
if (-not $serial) { throw 'Constrained emulator did not register with ADB within six minutes.' }

$booted = $false
for ($attempt = 0; $attempt -lt 180; $attempt++) {
    if ((& $adb -s $serial shell getprop sys.boot_completed).Trim() -eq '1') { $booted = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $booted) { throw 'Constrained emulator did not finish booting within six minutes.' }

$cpuCount = (& $adb -s $serial shell getconf _NPROCESSORS_ONLN).Trim()
$memory = & $adb -s $serial shell cat /proc/meminfo | Select-String '^MemTotal:'
$freqDriver = (& $adb -s $serial shell cat /sys/devices/system/cpu/cpufreq/policy0/scaling_driver).Trim()
$freqMin = (& $adb -s $serial shell cat /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq).Trim()
$freqMax = (& $adb -s $serial shell cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq).Trim()
$freqCurrent = (& $adb -s $serial shell cat /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq).Trim()
$npuServices = & $adb -s $serial shell service list | Select-String 'android.hardware.neuralnetworks'
$gles = & $adb -s $serial shell dumpsys SurfaceFlinger | Select-String '^GLES:' | Select-Object -First 1
Write-Output "ADB target: $serial"
Write-Output "Guest CPU count: $cpuCount"
Write-Output "Guest memory: $($memory.ToString().Trim())"
Write-Output "Guest CPU frequency interface: driver=$freqDriver, min=$freqMin, max=$freqMax, current=$freqCurrent"
Write-Output "Guest graphics renderer: $($gles.ToString().Trim())"
Write-Output "Neural-network services: $($npuServices -join '; ')"
