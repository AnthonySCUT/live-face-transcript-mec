# 实时人脸与语音记录

这是一个 Android 原型：在取得参与者同意后，应用实时显示摄像头画面中的多张人脸、识别已登记参与者、为陌生人分配会话编号，并记录中英混合语音的实时转写。

## 已实现功能

- **本机人脸处理**：CameraX 采集画面；YuNet/SFace 检测、提取特征并匹配本机 SQLite 身份库。
- **身份管理**：点击人脸后可登记；停止采集后可重命名或删除单个身份。删除身份不会删除历史转写。
- **陌生人编号**：未登记人脸在本次会话中显示为临时编号。
- **实时中英混合转写**：16 kHz 单声道麦克风 PCM 流发送至 FunASR WebSocket。临时结果实时显示，最终结果连同时间和说话人标签保存在本机。
- **离线后备**：FunASR 不可用时自动切换到本机 Vosk 中文识别；界面会明确提示后备状态。该后备不支持可靠的中英混合识别。
- **视觉说话人归属**：MediaPipe Face Landmarker 根据发声末段可见人脸的 `jawOpen` 变化，将文本关联到最有证据的说话人；证据不足或多人运动相近时显示“未确认说话人”。
- **隐私边界**：应用不保存原始音频；身份库与转写历史仅保存在设备本机。FunASR 模式会把实时音频流发送到配置的识别服务。

多人归属是基于嘴部可见运动的视觉判断，不包含声纹识别，也不分离同时发言的人声。约 2 秒的文字和说话人显示延迟是待真机实验验证的目标，不是已宣称的性能数据。

## 环境要求

- JDK 17
- Android SDK Platform 35、Build Tools 35.0.0 与 Platform Tools
- Android 8.0（API 26）或更高版本的模拟器或实体机
- 若要中英混合识别：可访问的 FunASR WebSocket 服务

项目使用 Gradle 8.9、Android Gradle Plugin 8.7.3 与 Kotlin 2.0.21。

## 构建

在仓库根目录的 PowerShell 中，按本机实际安装路径设置环境变量：

```powershell
$env:JAVA_HOME = 'D:\JDK17'
$androidSdk = 'C:\Program Files (x86)\Android\android-sdk'
$env:ANDROID_HOME = $androidSdk
$env:ANDROID_SDK_ROOT = $androidSdk
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

成功后 APK 位于：

```text
app\build\outputs\apk\debug\app-debug.apk
```

## 配置 FunASR

默认地址是 `ws://localhost:10095`。先按 [FunASR WebSocket 服务端说明](https://github.com/modelscope/FunASR/blob/main/runtime/python/websocket/funasr_wss_server.py)启动一个兼容的两遍流式服务，再把其端口暴露给 Android 设备。

### 联想模拟器或 USB 设备连接本机服务

模拟器已经连接时执行：

```powershell
$adb = Join-Path $androidSdk 'platform-tools\adb.exe'
& $adb devices -l
& $adb reverse tcp:10095 tcp:10095
```

这会把模拟器中的 `localhost:10095` 转发到开发电脑的同一端口，适用于本项目默认配置。每次重启模拟器后应重新执行一次 `adb reverse`。

如果团队使用其他服务地址，可在构建时覆盖：

```powershell
.\gradlew.bat :app:assembleDebug -PfunasrWsUrl=ws://192.168.1.10:10095
```

当前网络安全配置只为 `localhost` 和 `10.0.2.2` 允许明文 `ws://`。外部服务应优先使用 `wss://`；若必须使用其他明文内网地址，需显式修改 `app/src/main/res/xml/network_security_config.xml`，不要全局放开明文流量。

## 安装和启动

先在联想模拟器中启用主机摄像头和麦克风，随后执行：

```powershell
$adb = Join-Path $androidSdk 'platform-tools\adb.exe'
& $adb devices -l
& $adb install --no-streaming -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb shell monkey -p edu.scut.livetranscript 1
```

`--no-streaming` 用于避免该模拟器安装大 APK 时卡住；`-r` 会保留已登记身份和转写记录。除非明确要清除测试数据，请勿使用 `adb uninstall` 或 `pm clear`。

## 使用与人工验收

1. 打开应用，取得参与者同意后勾选同意框，点击“开始采集”。
2. 确认摄像头画面出现完整人脸框；点击某张脸可登记身份。停止采集后用“管理身份”重命名或删除重复记录。
3. 确认“实时转写”显示麦克风电平。持续低于 `-55 dBFS` 时，检查模拟器麦克风转发、Windows 输入设备和应用权限。
4. 确认状态显示 FunASR 中英混合识别；依次说中文、英文以及同一句中英混说，例如 `How are you? 我们来测试中英混说。`。
5. 两人同时入镜但轮流发言，确认转写优先归属嘴部有明显运动的人；将嘴部遮挡、两人同时讲话或运动证据接近时，预期结果可以是“未确认说话人”。
6. 停止采集后确认摄像头和录音释放，并在“已保存转写”中检查最终文字、时间和说话人标签。

可复现实验的语句、测量字段和记录表见 [docs/experiments/live-transcription-protocol.md](docs/experiments/live-transcription-protocol.md)。

## 模块结构

```text
CameraX ──> YuNet/SFace ──> FaceTracker ──> 身份标签 / 陌生人编号
   │
   └──────> MediaPipe Face Landmarker ──> 嘴部活动 ──> SpeakerAttributor

AudioRecord (16 kHz PCM) ──> FunASR WebSocket ──> 临时文本 / 最终历史
                              └──服务不可用──> Vosk 中文后备
```

后续开发、部署排查和交接信息见 [AGENT_HANDOFF.md](AGENT_HANDOFF.md)，协作流程见 [CONTRIBUTING.md](CONTRIBUTING.md)，课程实验路线见 [ROADMAP.md](ROADMAP.md)。
