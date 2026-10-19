# 实时人脸识别与语音转录

面向《软件体系结构》移动边缘计算课程项目的 Android 原型。应用使用 CameraX 持续读取视频，在本机执行多阶段人脸检测与身份比对，并将麦克风中的中文语音离线转写为文字，关联到已登记身份或当前会话内的陌生人编号。

## 功能

- **实时视频识别**：CameraX 视频帧 -> YUV 转换 -> OpenCV YuNet 多人脸检测 -> SFace 对齐与特征提取 -> 本机身份比对 -> 跨帧跟踪 -> 画面人脸框与姓名标签。
- **实时语音转录**：Android `AudioRecord` 采集 16 kHz PCM -> dBFS 输入电平 -> Vosk 离线中文语音识别 -> 说话人关联 -> 本机 SQLite 转写历史。
- 已登记的人显示登记姓名；未知人显示 `陌生人 A`、`陌生人 B` 等会话内编号。陌生人编号在应用会话结束后重置。
- 转写历史保存文本、时间和说话人标签。应用不保存或上传原始音频、人脸特征或转写内容。
- 单人画面默认把语音关联到该人。多人同框时需要用户点选正在说话的人脸；项目没有声纹分离，不应宣称能仅靠语音自动判断说话人。
- 开始采集前需勾选参与者知情同意。录音面板显示 dBFS；低于 `-55 dBFS` 会提示没有明显声音，识别输出还要求语音能量达到 `-52 dBFS` 门限，以减少低音量噪声造成的误识别。

## 技术栈

| 部分 | 技术 |
| --- | --- |
| 客户端 | Android、Kotlin、CameraX、Material Components |
| 人脸流水线 | OpenCV 4.10、YuNet、SFace |
| 语音流水线 | Android AudioRecord、Vosk Android 0.3.75、vosk-model-small-cn-0.22 |
| 本地存储 | SQLite（Android 私有应用数据目录） |
| 构建 | Gradle Wrapper 8.9、Android Gradle Plugin 8.7.3、Kotlin 2.0.21 |

## 项目结构

```text
app/src/main/java/edu/scut/livetranscript/
  MainActivity.kt       界面、权限、摄像头/麦克风生命周期、语音识别与转写
  FacePipeline.kt       YuNet 检测、SFace 对齐和特征提取
  FaceTracker.kt        跨帧跟踪、身份匹配、陌生人编号
  FaceOverlayView.kt    人脸框、姓名标签、触摸选择说话人
  LocalStore.kt         本机身份库和转写历史
app/src/main/assets/models/
  face_detection_yunet_2023mar.onnx
  face_recognition_sface_2021dec.onnx
  vosk-model-small-cn-0.22/     离线中文识别模型
AGENT_HANDOFF.md        完整模拟器设置、安装步骤和 Agent 交接提示
```

## 环境要求

- Windows 10/11 或 Android Studio 支持的平台。
- JDK 17 或 21。
- Android SDK Platform 35、Build Tools 35.0.0、Android Emulator、Platform Tools。
- Android 设备最低 API 26（Android 8.0）。当前开发验证使用 API 30 AVD。
- 首次构建需要网络下载 Gradle 和 Maven 依赖。

## 获取源码

```powershell
git clone https://github.com/anthonyscut/live-face-transcript-mec.git
cd live-face-transcript
```

在 Android Studio 中打开工程目录 `live-face-transcript/`。若在 PowerShell 命令行构建：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

Debug APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

也可以在 Android Studio 使用 **Build > Make Project** 或运行 Gradle 任务 `:app:assembleDebug`。APK 包含离线语音模型与原生推理库，约 265 MB；首次启动时会将约 68 MB 的 Vosk 模型展开到应用私有存储。APK 和构建缓存不提交到 Git。

## 安装到 Android 设备/已启动模拟器

确认设备已通过 USB 调试或 Android Emulator 连上 ADB，然后运行：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
& $adb install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb shell monkey -p edu.scut.livetranscript 1
```

`-r` 会保留现有登记与历史数据并更新应用。不要用 `adb uninstall` 或 `adb shell pm clear`，除非明确决定清除这些数据。

## 创建 Android 虚拟机

1. Android Studio -> **Tools > Device Manager > Create Virtual Device**。
2. 选择 Pixel 设备，下载 API 30 或更高版本的 x86_64 系统镜像。
3. 在 **Show Advanced Settings** 中把后置摄像头设为 **Webcam0**；没有可用主机摄像头时可用 `Emulated`，但它可能显示生成的测试场景或色块。
4. 创建并启动 AVD，使用 `adb devices -l` 确认状态为 `device`。
5. 为采集主机麦克风，确认 Windows 麦克风隐私设置允许桌面应用访问，再连接模拟器的主机音频输入：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb emu avd hostmicon
```

当前开发机已有 `GestureMEC_API_30`，安装了 API 30 镜像，可通过以下命令用主机摄像头启动：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" `
  -avd GestureMEC_API_30 -no-snapshot-load `
  -camera-front none -camera-back webcam0
```

如果该 AVD 已运行，请复用现有设备，不要启动第二个同名实例。更详细说明见 [AGENT_HANDOFF.md](AGENT_HANDOFF.md)。

## 使用流程

1. 先告知参与者并取得同意，再打开应用、勾选同意框并按 **开始采集**。
2. 首次启动需等待 Vosk 模型加载；相机画面、人脸检测和输入电平会显示在主界面。
3. 登记参与者时，选择画面中的脸，输入本人姓名并按 **登记选中人脸**。只有本人同意后才登记。
4. 对着主机麦克风说中文，确认 dBFS 随说话升高、实时文字出现，并查看本机保存的转写记录。
5. 多人同框时，点选实际说话人的脸；未点选时转写记为 `未确认说话人`。
6. 按 **停止采集** 结束会话并释放摄像头与麦克风。

## 麦克风排查

- Android 权限授权不代表麦克风已经获得有效声音；会话内必须同时检查实时 dBFS 和转写结果。
- 电平长期低于 `-55 dBFS`：确认 Windows 输入设备未静音、Windows 麦克风隐私允许桌面应用、模拟器已运行 `hostmicon`，并靠近麦克风说话。
- 电平高于 `-52 dBFS` 但没有转写：检查 Vosk 状态、中文发音、`adb logcat` 中的 AudioRecord/Vosk 错误，并用实体 Android 手机复测。
- 查看日志：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -d |
  Select-String 'AudioRecord|AudioFlinger|VoskAPI|FATAL EXCEPTION|livetranscript'
```

## 团队协作

- 默认协作分支为 `main`；每个功能或修复从 `main` 建立短期分支，例如 `feature/audio-meter`、`fix/face-tracking`。
- 通过 Pull Request 合并；PR 描述需包含改动目的、复现/验证步骤、模拟器或实体机信息及截图（确认参与者同意后才提交含人物的截图）。
- 不提交 `local.properties`、Gradle 缓存、`app/build/`、APK、录音、人脸登记数据库或个人测试数据。
- 小组成员需由仓库所有者在 GitHub 私有仓库设置中添加为 Collaborators。克隆后请各自确认 Android SDK/JDK 路径，不要把个人绝对路径提交到工程配置。
- 若要对外公开项目，先确认 OpenCV/Vosk 依赖、模型文件及测试图片的再分发许可；当前仓库用于小组开发，不额外声明项目代码许可证。

## 维护与已知限制

- 人脸匹配质量受光照、角度、距离、遮挡和相机质量影响；登记库只保存在当前设备。
- 未知人编号为会话级身份，不保证跨天或跨会话追踪同一陌生人。
- 没有声纹识别或自动说话人分离；多人场景需手动选择说话人。
- 模拟器性能、摄像头画质和麦克风路由不等同真实手机，应在目标设备复测。
- Vosk Android 库和中文模型、OpenCV 及人脸模型依各自上游许可；模型来源与 Agent 执行清单见交接说明。
