# 实时人脸与语音记录

这是一个 Android 原型：在取得参与者同意后，应用实时显示摄像头画面中的多张人脸、识别已登记参与者、为陌生人分配会话编号，并记录中英混合语音的实时转写。

## 已实现功能

- **本机人脸处理**：CameraX 采集画面；YuNet/SFace 检测、提取特征并匹配本机 SQLite 身份库。
- **身份管理**：点击人脸后可登记；停止采集后可重命名或删除单个身份。删除身份不会删除历史转写。
- **陌生人编号**：未登记人脸在本次会话中显示为临时编号。
- **实时中英混合转写**：16 kHz 单声道麦克风 PCM 流发送至 FunASR WebSocket。临时结果实时显示，最终结果连同时间和说话人标签保存在本机。
- **整句识别修订**：Paraformer 提供实时草稿，SenseVoiceSmall 对完整语句进行中英混合最终识别并补充标点；最终句覆盖草稿后保存。已登记姓名会作为 FunASR 热词上下文。
- **字幕人工校正**：点按“已保存转写”中的任意字幕，编辑后保存即可覆盖本机对应文字，时间和说话人记录保留。
- **默认电脑端中英识别**：FunASR 以完整语句生成最终字幕；电脑端不可用时自动切回手机中英双模型 Vosk。Vosk 结合词级置信度、语言特征和文本/时长比例筛选候选，并清除与 final 时间重叠的迟到草稿，减少英文背景声产生伪中文或重复字幕。选择“本地中英”可进行纯手机端识别与 Part A/C 实验。
- **人脸与声纹双通道身份**：登记人脸后，可在采集期间录制 5 秒声纹。应用从时间戳对应的 PCM 片段提取 MFCC 统计特征，在本机匹配身份库；声纹匹配与嘴部活动共同关联字幕和人脸框。全帧嘴部关键点未关联的小脸轨迹会按人脸框局部裁切并放大后分析，再按轨迹 ID 对齐。人脸被遮挡时仍可用声纹标注已登记说话人；两路身份冲突时显示“身份待复核”。
- **多人重叠分离**：MediaPipe 检测多张脸的同步嘴部活动后，电脑端 SepFormer 将 PCM 拆成两路，再并行运行两个 FunASR 会话；手机结合登记声纹与嘴部活动—分离音频能量相关性，为每路字幕匹配身份。证据不足时保留独立的陌生人编号。
- **MEC 计算切分**：支持手机本地、检测对齐后把人脸小图发至电脑、整帧发至电脑，以及基于网络和性能剖析的动态切换。支持输入尺寸、上行限速、实验 CSV 导出与样例实验；操作及指标定义见 [端云实验指南](docs/experiments/mec-partitioning.md)。
- **本机数据**：原始音频仅在内存中保留最近 12 秒用于按时间对齐字幕；声纹特征、人脸特征和转写历史保存在设备 SQLite 中。FunASR 模式会把实时音频流发送到配置的识别服务。

遮挡跟踪保留时间为 8 秒，画面暂时无人脸时字幕仍可由声纹标注已登记身份；人物在保留时间内回到画面时沿用原轨迹。固定回放素材覆盖电影快节奏对话、多人同框、遮挡恢复、出画重入和双人同时发言，并写入 CSV 日志。

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

为模拟器和 VIVO X200 Pro 构建单 ABI 安装包（路径相同，按最后一次构建参数生成）：

```powershell
.\gradlew.bat :app:assembleDebug -PtargetAbis=x86_64
.\gradlew.bat :app:assembleDebug -PtargetAbis=arm64-v8a
```

## 启动电脑端服务

电脑端提供三个服务：人脸 MEC HTTP `8000`、FunASR 中英混合 WebSocket `10095`、SepFormer 分离加双 FunASR 并行转写 HTTP `8001`。FunASR 使用 Paraformer 输出实时草稿、SenseVoiceSmall 生成中英混合最终结果；最终句覆盖实时草稿。

Python 3.12 下分别创建人脸服务和 GPU 语音服务环境：

```powershell
py -3.12 -m venv server\.venv-face
server\.venv-face\Scripts\python.exe -m pip install -r server\requirements.txt
py -3.12 -m venv server\.venv-asr
$asrPython = 'server\.venv-asr\Scripts\python.exe'
& $asrPython -m pip install torch==2.11.0+cu128 torchaudio==2.11.0+cu128 --index-url https://download.pytorch.org/whl/cu128
& $asrPython -m pip install -r server\requirements-asr.txt
```

启动三项本机服务：

```powershell
& .\server\start_local_services.ps1
```

RTX 5060 使用 CUDA 12.8 推理。首次启动会从 ModelScope 与 Hugging Face 下载 FunASR、VAD、SepFormer 权重；加载完成后服务开始监听。启动日志保存在 `server\artifacts\logs`。关闭服务用 `& .\server\stop_local_services.ps1`。

### 手机一键连接电脑服务

在项目根目录双击 `打开手机连接助手.bat`，然后点击“启动服务并连接手机”。连接手机需要一根支持数据传输的 USB 线，并在手机中开启 USB 调试；首次连接时解锁手机并确认 RSA 授权。助手会启动/复用人脸 MEC、FunASR 和双人分离服务，为 `8000`、`8001`、`10095` 建立 ADB USB 反向转发，按设备 ABI 安装 APK 并打开应用。应用自动选用 USB 转发地址并检查三项服务。

实体手机通过 `127.0.0.1` 访问反向转发端口；模拟器继续使用 `10.0.2.2` 访问电脑端人脸和分离服务。数据通过 USB 传输，因此手机与电脑不必在同一 Wi-Fi，VPN 的 IP 路由也不参与这条连接路径。电脑端需要已配置好仓库中的 Python 环境和模型；首次模型下载期间等待服务启动完成。

若改用 Wi-Fi 直连，电脑和手机需要有互相可达的局域网地址，应用连接地址可在实验区的三个服务地址栏修改。VPN 网络策略、路由隔离、设备未授权或 USB 线仅支持充电时，连接助手会在窗口日志中给出检测结果和对应处理步骤。

## 手机硬件约束实验

运行 `scripts\Start-MobileProfileEmulator.ps1` 可用同一 `MEC_API_35` AVD 启动可复现的受限配置：2 个 guest CPU 核、2048 MB RAM、SwiftShader 软件图形渲染，并保留 AVD 用户数据。Android Emulator 官方支持用 `-cores`、`-memory` 和 `-gpu software` 选择这些配置；软件渲染后可在 `dumpsys SurfaceFlinger` 中核对 `Google SwiftShader`。[Emulator 命令行选项](https://developer.android.com/studio/run/emulator-commandline) · [图形加速配置](https://developer.android.com/studio/run/emulator-acceleration)

该 AVD 的 CPU frequency driver 记录为 `dummy`，frequency min/max 字段记录为 `1/2`；实验将其作为模拟器报告值保存，并把可控 CPU 约束明确记为 2 个 guest 核。`-gpu software` 使用 SwiftShader 屏幕渲染；本次图形配置按软件渲染记录。当前本地推理路径为 OpenCV YuNet/SFace、默认 CPU delegate 的 MediaPipe FaceLandmarker 和 Vosk；实验记录这一 CPU 推理配置。实时摄像头身份分析的帧间隔为 450 ms，嘴部分析为 200 ms。AVD 的 NNAPI 服务列为 `nnapi-sample_*`，硬件 NPU 性能数据在 VIVO X200 Pro 实机采集。NNAPI 已在 Android 15 弃用，Android 官方推荐新模型使用 TensorFlow Lite GPU runtime 等更新路径。[NNAPI 文档](https://developer.android.com/ndk/guides/neuralnetworks) · [NNAPI 迁移指南](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)

手机 GPU/NPU 与真实 CPU 频率的数据在 VIVO X200 Pro 实机上运行同一 APK 采集；当前 AVD 报告记录 vCPU、RAM、软件图形渲染、frequency driver 值和应用帧率约束。

“远端服务不可用”表示 WebSocket 尚未连上 `10095`：服务未启动、模型仍在加载、USB 转发未建立，或服务地址/端口不匹配都会触发该状态。重新打开桌面连接助手并检查应用中的“电脑服务连接”状态；随后停止并重新开始采集以建立新的 FunASR 会话。

如果团队使用其他服务地址，可在构建时覆盖：

```powershell
.\gradlew.bat :app:assembleDebug -PfunasrWsUrl=ws://192.168.1.10:10095
```

当前网络配置支持内网明文 HTTP/WebSocket，供手机连接电脑上的 MEC 和 FunASR 服务。

## 安装和启动

使用 Android Emulator 的 `MEC_API_35` AVD 时，先启用主机摄像头和麦克风：

```powershell
$androidSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$emulator = Join-Path $androidSdk 'emulator\emulator.exe'
Start-Process -FilePath $emulator -ArgumentList @(
  '-avd', 'MEC_API_35',
  '-camera-back', 'webcam0',
  '-camera-front', 'none',
  '-legacy-fake-camera',
  '-allow-host-audio',
  '-no-snapshot-load'
)
```

模拟器启动后打开主机麦克风转发，再安装应用。麦克风输入来自 Windows 默认录音设备；扬声器播放不参与录音链路。

```powershell
$adb = Join-Path $androidSdk 'platform-tools\adb.exe'
& $adb devices -l
& $adb wait-for-device
& $adb emu avd hostmicon
& $adb install --no-streaming -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb shell monkey -p edu.scut.livetranscript 1
```

`--no-streaming` 用于避免该模拟器安装大 APK 时卡住；`-r` 会保留已登记身份和转写记录。除非明确要清除测试数据，请勿使用 `adb uninstall` 或 `pm clear`。

## 使用与人工验收

1. 打开应用，语音位置默认为“电脑端中英”；取得参与者同意后勾选同意框，点击“开始采集”。FunASR 不可用时会回退到本地中英模型；Part A/C 本地条件选择“本地中英”。
2. 确认摄像头画面出现完整人脸框；点击某张脸并填写姓名，点“登记选中人脸”。开始采集后再次点选此人，点“录制选中人的声纹（5 秒）”，持续说话完成声纹登记。
3. 确认“实时转写”显示麦克风电平。持续低于 `-55 dBFS` 时，检查模拟器麦克风转发、Windows 输入设备和应用权限。
4. 确认状态显示 FunASR 中英混合识别；依次说中文、英文以及同一句中英混说，例如 `How are you? 我们来测试中英混说。`。
5. 两人同时入镜并轮流发言时，确认声纹与嘴部证据都能把字幕关联到身份；遮挡人脸时检查声纹标签；两人同时讲话时检查多人候选和人脸高亮。
6. 停止采集后确认摄像头和录音释放，并在“已保存转写”中检查最终文字、时间和说话人标签。

可复现视频、WAV、来源信息和 SHA-256 清单见 [testdata/reproducible-video/README.md](testdata/reproducible-video/README.md)。运行“导出边界场景诊断 CSV”取得嘴部 Landmarker 匹配数、活动样本和重叠轨迹；运行“导出复现实验 CSV”取得 Part B/C、端云字节、语音文本/时延和电池读数。端云静态/动态实验方法见 [docs/experiments/mec-partitioning.md](docs/experiments/mec-partitioning.md)。

## 模块结构

```text
CameraX ──> YuNet/SFace ──> FaceTracker ──> 身份标签 / 陌生人编号
   │
   └──────> MediaPipe Face Landmarker ──> 嘴部活动 ──> SpeakerAttributor

AudioRecord PCM ──> 12 秒内存时间窗 ──> MFCC 声纹匹配 ─┐
       └──────────> FunASR / Vosk ──> 文本时间戳 ────┼─> 身份融合 / 实时字幕
视频帧 ──> FaceTracker + 嘴部活动 ───────────────────┘
```

后续开发、部署排查和交接信息见 [AGENT_HANDOFF.md](AGENT_HANDOFF.md)，协作流程见 [CONTRIBUTING.md](CONTRIBUTING.md)，课程实验路线见 [ROADMAP.md](ROADMAP.md)。
