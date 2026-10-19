# 实时人脸识别与语音转录：交接说明

这份文件介绍 Android 项目如何构建、安装、启动和验证，也包含可直接交给另一个 Agent 的完整任务提示。请把整个 `live-face-transcript` 文件夹提供给 Agent；如果只能拖一个文件，就拖本说明，并确保 Agent 能访问同目录的源码和模型资源。

## 项目位置

- 工程目录：`outputs/live-face-transcript/`
- 本说明：`outputs/live-face-transcript/AGENT_HANDOFF.md`
- 简要 README：`outputs/live-face-transcript/README.md`
- Android 客户端源码：`outputs/live-face-transcript/app/src/main/java/edu/scut/livetranscript/`
- 人脸模型和离线中文语音模型：`outputs/live-face-transcript/app/src/main/assets/`
- 可安装 Debug APK：`outputs/live-face-transcript/app/build/outputs/apk/debug/app-debug.apk`
- Android application ID：`edu.scut.livetranscript`

当前 Debug APK 约 265 MB。APK 内包含 YuNet/SFace 人脸模型、Vosk 离线中文模型及 Android 原生推理库。首次运行时，Vosk 会将约 68 MB 的解压模型文件复制到应用私有目录；之后会复用本机副本。

## 功能与限制

- CameraX 持续读取摄像头画面，OpenCV YuNet 检测多张人脸，SFace 提取特征并与本机 SQLite 身份库比对。
- 已登记的人显示登记姓名；未知人使用 `陌生人 A`、`陌生人 B` 等会话内编号。未知人编号只在本次应用会话中有效。
- 离线 Vosk 中文模型将麦克风语音转为文字；界面显示实时转写，识别结果、时间戳和说话人标签保存在本机 SQLite。应用不保存原始音频，也不上传人脸特征或转写文本。
- 实时转写卡片显示麦克风输入电平（dBFS）。低于 `-55 dBFS` 时界面提示没有明显声音；语音能量门限为 `-52 dBFS`，低能量背景噪声不会显示或写入转写历史。
- 多人同框时没有声纹说话人分离。用户须点选画面中的脸指定说话人；未指定时会标为 `未确认说话人`。单人画面则自动关联到该人。
- 开始按钮要求先勾选参与者已知情并同意。只对明确同意参与测试的人开启摄像头和麦克风。

主要文件：

- `app/src/main/java/edu/scut/livetranscript/MainActivity.kt`：界面、权限、CameraX 生命周期、录音、电平显示、Vosk 和转写保存。
- `app/src/main/java/edu/scut/livetranscript/FacePipeline.kt`：YuNet 检测、SFace 对齐与特征提取。
- `app/src/main/java/edu/scut/livetranscript/FaceTracker.kt`：跨帧人脸关联、已登记身份匹配和陌生人编号。
- `app/src/main/java/edu/scut/livetranscript/FaceOverlayView.kt`：视频画面人脸框、姓名标签和说话人选择。
- `app/src/main/java/edu/scut/livetranscript/LocalStore.kt`：本机 SQLite 身份库和转写历史。

## 环境要求

- Windows 10/11、Android Studio 和 Android SDK。
- JDK 17 或 21。当前开发机使用 `C:\Program Files\Android\openjdk\jdk-21.0.8`。
- Android SDK Platform 35、Android Build Tools 35.0.0、Android Emulator、Platform Tools。
- 工程 Gradle Wrapper 为 Gradle 8.9；Android Gradle Plugin 为 8.7.3，Kotlin 插件为 2.0.21。
- Android 最低版本 API 26（Android 8.0）。当前测试模拟器为 API 30。

## 构建 Debug APK

在 PowerShell 中进入工程目录后运行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

成功产物：

```text
app\build\outputs\apk\debug\app-debug.apk
```

如果使用其他电脑，请按实际 JDK/Android SDK 安装位置修改环境变量。首次构建需要下载 Gradle 和 Maven 依赖。

## 安装到已运行的 Android 模拟器

确认 Android Studio Device Manager 已启动一个模拟器，随后在工程目录执行：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
& $adb install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb shell monkey -p edu.scut.livetranscript 1
```

`-r` 会更新 APK 并保留应用数据。不要使用 `adb uninstall` 或 `pm clear`，除非用户明确要求清除本机的人脸登记和转写记录。

## 首次创建 Android 虚拟机

1. 在 Android Studio 打开 **Tools > Device Manager > Create Virtual Device**。
2. 选择 Pixel 设备，例如 Pixel 6；下载并选择 API 30 或更新的 x86_64 系统镜像。
3. 在 AVD 的 **Show Advanced Settings** 中设置摄像头后置来源为 **Webcam0**。前置摄像头可设为 None。
4. 创建并启动 AVD，然后用 `adb devices -l` 确认设备状态为 `device`。
5. 如虚拟画面显示彩色块，通常是使用了 AVD 的 `emulated` 生成场景。改为 **Webcam0**，或在启动命令指定真实主机摄像头。
6. 如应用拿不到主机麦克风声音，检查 Windows **Settings > Privacy & security > Microphone**，允许麦克风访问及桌面应用访问；再运行下方的 `hostmicon` 命令。

当前开发机已创建 `GestureMEC_API_30`，Android SDK 在 `%LOCALAPPDATA%\Android\Sdk`，并可使用主机摄像头 `webcam0`。通常不需要重新创建：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" `
  -avd GestureMEC_API_30 -no-snapshot-load `
  -camera-front none -camera-back webcam0
```

若该 AVD 已经运行，不要再启动第二个同名模拟器。先用 `adb devices -l` 检查当前设备。

## 模拟器麦克风设置

给 Android 应用授权录音权限后，还需把模拟器音频输入连接到 Windows 主机麦克风：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb emu avd hostmicon
```

应用仅在勾选同意并按下 **开始采集** 后申请相机和麦克风权限。进入会话后，查看 **实时转写** 卡片的 dBFS 数值：

- 电平随说话明显上升，说明 Android 已收到麦克风数据。
- 长期低于 `-55 dBFS`：检查 Windows 麦克风隐私权限、系统输入设备/静音、模拟器 `hostmicon` 状态，以及麦克风与说话者的距离。
- 超过 `-52 dBFS` 后才会把语音片段交给转写显示/保存门限；若电平正常而没有文字，再检查 Vosk 模型加载、中文发音和 `adb logcat`。

## 使用与验证流程

1. 打开 **Live Face Transcript**，确认参与者已知情并同意后勾选同意框。
2. 按 **开始采集**。首次启动会加载/复制离线模型，稍等状态变为本机离线中文识别运行中。
3. 确认主机摄像头画面正常，面对摄像头；画面应显示人脸框及姓名或陌生人编号。
4. 将登记姓名填入参与者登记栏，点选对应人脸，再按 **登记选中人脸**。后续画面应显示登记姓名。
5. 对着主机麦克风说一段简短中文。确认 dBFS 电平上升、实时转写出现，并检查转写历史中的时间和说话人标签。
6. 多人同框时点选正在讲话的人脸，再测试说话人关联。此项目没有自动声纹分离功能。
7. 按 **停止采集**，确认摄像头和麦克风已释放；检查日志没有 `FATAL EXCEPTION`、AudioRecord 初始化/读取错误。

常用诊断命令：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -d |
  Select-String 'AudioRecord|AudioFlinger|VoskAPI|FATAL EXCEPTION|livetranscript'
```

在 Android `dumpsys audio` 中，正常采集时可看到 `edu.scut.livetranscript` 的 `VOICE_RECOGNITION` 录音会话；`dumpsys media.audio_flinger` 可检查输入设备和信号电平。只看到权限授权或界面 `MIC ON` 还不足以证明有有效声音，需同时确认 dBFS 电平变化和转写结果。

## 交给另一个 Agent 的任务提示

把本说明与 `outputs/live-face-transcript/` 项目目录一起拖给 Agent，并使用下面这段提示：

```text
请接手“实时人脸识别与语音转录”Android 项目并把整个开发、安装、模拟器配置和功能验证流程做完。先阅读 AGENT_HANDOFF.md 和 README.md，再检查现有源码与 APK；沿用现有 CameraX、OpenCV YuNet/SFace、Vosk 离线中文识别、AudioRecord 电平显示和 SQLite 实现。

目标：在 Android 模拟器/设备上实时显示摄像头中的人脸标签，把中文语音实时转成文字并关联到已登记姓名或陌生人会话编号，且将转写文本、时间和标签保存在本机。不要保存或上传原始音频、人脸特征、转写文本。多人同时出现时由用户点选说话人，不要声称具备自动声纹分离。

请从当前工作目录探测实际 JDK、Android SDK、ADB、Gradle 和 AVD 路径；构建 Debug APK；启动或复用现有 AVD；将摄像头设置为主机 Webcam0，并打开 AVD hostmicon；安装 APK。只有在参与者明确同意后才勾选界面同意框并启动摄像头/麦克风。

完成端到端验证：确认真实视频画面、至少一个人脸框及姓名/陌生人标签；确认麦克风 dBFS 随说话上升、Vosk 显示中文转写、SQLite 历史保存说话人和时间；确认停止后相机及录音释放。用日志和界面状态定位并修复失败项。不要清除用户已有应用数据、登记库或历史记录。最后更新本说明，汇报 APK 路径、构建结果、实际验证证据、未完成项与限制。若当前环境没有可用麦克风/摄像头或未取得参与者同意，完成能做的工作并准确说明阻塞点，不要伪造测试成功。
```

## 已知实现限制

- API 30 模拟器可用于功能开发，但其摄像头清晰度和 CPU 性能不代表真实手机效果；最终应在目标 Android 手机上复核。
- Vosk 中文小模型离线运行。首次加载需要一定时间和约 68 MB 私有存储空间；完整 APK 较大。
- 人脸识别效果取决于光照、姿态和摄像头质量。登记前应确认参与者同意，登记数据只存在应用本机数据库。
- 多人场景下的说话人关联依赖用户点选，无法仅凭语音自动确认具体说话者。
