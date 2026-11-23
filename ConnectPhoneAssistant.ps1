Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$root = $PSScriptRoot
$workerScript = Join-Path $root 'ConnectPhoneWorker.ps1'
$logPath = Join-Path $env:TEMP 'LiveFaceTranscript-ConnectPhone.log'
$errorPath = Join-Path $env:TEMP 'LiveFaceTranscript-ConnectPhone-error.log'
$script:workerProcess = $null

$form = New-Object System.Windows.Forms.Form
$form.Text = 'Live Face Transcript · 手机连接助手'
$form.StartPosition = 'CenterScreen'
$form.ClientSize = New-Object System.Drawing.Size(700, 430)
$form.MinimumSize = New-Object System.Drawing.Size(700, 430)
$form.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 9)

$title = New-Object System.Windows.Forms.Label
$title.Text = '一键启动电脑服务并连接 Android 手机'
$title.Location = New-Object System.Drawing.Point(18, 16)
$title.Size = New-Object System.Drawing.Size(650, 30)
$title.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 14, [System.Drawing.FontStyle]::Bold)
$form.Controls.Add($title)

$hint = New-Object System.Windows.Forms.Label
$hint.Text = '使用 USB 数据线并开启 USB 调试；首次连接请在手机上确认“允许 USB 调试”。USB 端口转发可绕过 Wi-Fi 与 VPN 路由。'
$hint.Location = New-Object System.Drawing.Point(20, 52)
$hint.Size = New-Object System.Drawing.Size(650, 42)
$form.Controls.Add($hint)

$startButton = New-Object System.Windows.Forms.Button
$startButton.Text = '启动服务并连接手机'
$startButton.Location = New-Object System.Drawing.Point(20, 102)
$startButton.Size = New-Object System.Drawing.Size(210, 42)
$form.Controls.Add($startButton)

$stopButton = New-Object System.Windows.Forms.Button
$stopButton.Text = '断开转发并停止服务'
$stopButton.Location = New-Object System.Drawing.Point(240, 102)
$stopButton.Size = New-Object System.Drawing.Size(210, 42)
$form.Controls.Add($stopButton)

$status = New-Object System.Windows.Forms.Label
$status.Text = '等待操作'
$status.Location = New-Object System.Drawing.Point(20, 154)
$status.Size = New-Object System.Drawing.Size(650, 24)
$form.Controls.Add($status)

$logBox = New-Object System.Windows.Forms.TextBox
$logBox.Location = New-Object System.Drawing.Point(20, 182)
$logBox.Size = New-Object System.Drawing.Size(650, 220)
$logBox.Multiline = $true
$logBox.ReadOnly = $true
$logBox.ScrollBars = 'Vertical'
$logBox.WordWrap = $true
$logBox.Font = New-Object System.Drawing.Font('Consolas', 9)
$form.Controls.Add($logBox)

function Start-ConnectionWorker([bool]$stop) {
    if ($script:workerProcess -and -not $script:workerProcess.HasExited) { return }
    Set-Content -LiteralPath $logPath -Value '' -Encoding UTF8
    Set-Content -LiteralPath $errorPath -Value '' -Encoding UTF8
    $arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$workerScript`""
    if ($stop) { $arguments += ' -Stop' }
    $script:workerProcess = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $logPath -RedirectStandardError $errorPath
    $startButton.Enabled = $false
    $stopButton.Enabled = $false
    $status.Text = if ($stop) { '正在撤销转发并停止服务…' } else { '正在检查手机、启动服务并安装应用…' }
}

$startButton.Add_Click({ Start-ConnectionWorker $false })
$stopButton.Add_Click({ Start-ConnectionWorker $true })

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 700
$timer.Add_Tick({
    if (Test-Path -LiteralPath $logPath) { $logBox.Text = Get-Content -LiteralPath $logPath -Raw -Encoding UTF8 }
    if ($script:workerProcess -and $script:workerProcess.HasExited) {
        $timer.Stop()
        if (Test-Path -LiteralPath $errorPath) {
            $errors = Get-Content -LiteralPath $errorPath -Raw -Encoding UTF8
            if ($errors.Trim()) { $logBox.AppendText("`r`n$errors") }
        }
        if ($script:workerProcess.ExitCode -eq 0) { $status.Text = '操作完成' } else { $status.Text = "操作结束，退出码 $($script:workerProcess.ExitCode)；请查看下方日志" }
        $startButton.Enabled = $true
        $stopButton.Enabled = $true
        $logBox.SelectionStart = $logBox.TextLength
        $logBox.ScrollToCaret()
    }
})
$startButton.Add_Click({ $timer.Start() })
$stopButton.Add_Click({ $timer.Start() })

$form.Add_Shown({ $form.Activate() })
[void]$form.ShowDialog()
