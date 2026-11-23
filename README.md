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

模拟器重启后，需要把 `10095` 端口反向转发到电脑：

### 联想模拟器或 USB 设备连接本机服务

模拟器已经连接时执行：

```powershell
$androidSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $androidSdk 'platform-tools\adb.exe'
& $adb devices -l
& $adb reverse tcp:10095 tcp:10095
```

应用默认以 `10.0.2.2:8000` 和 `10.0.2.2:8001` 访问模拟器宿主机；`localhost:10095` 通过上面的 `adb reverse` 连接 FunASR。实体手机用 USB 调试时，同样可使用 `adb reverse tcp:10095 tcp:10095`，MEC 地址填电脑局域网 IPv4，例如 `http://192.168.1.10:8000` 和 `http://192.168.1.10:8001/separate-and-transcribe`。也可将 FunASR 地址填写为 `ws://192.168.1.10:10095`。

“远端服务不可用”表示 WebSocket 尚未连上 `10095`：常见情况是服务未启动、模型仍在加载尚未开始监听、模拟器重启后没有恢复 `adb reverse`，或自定义服务地址/端口不匹配。恢复服务和转发后，停止并重新开始采集以建立新的 FunASR 会话；当前会话会继续使用启动时选择的后备引擎。

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
