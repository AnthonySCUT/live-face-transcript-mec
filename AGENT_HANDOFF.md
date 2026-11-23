# 实时人脸与语音记录：Agent 交接指南

把整个仓库根目录交给下一位 Agent。先阅读本文件、[README.md](README.md)、[CONTRIBUTING.md](CONTRIBUTING.md) 和当前 `git status`，再修改代码。不要假设仓库所在磁盘或模拟器端口固定。

## 当前项目状态

- Android application ID：`edu.scut.livetranscript`。
- 最低 API 26，目标/编译 API 35，JDK 17，Gradle 8.9。
- 已实现本机多脸检测、SFace 身份匹配、会话内陌生人编号、身份重命名/删除、本机 SQLite 转写历史。
- 默认语音模式是电脑端 FunASR 中英混合识别；可切换到手机本地中英双模型、中文单模型或英文单模型。电脑端使用 `ws://localhost:10095` 两遍流式识别，Paraformer 提供实时草稿，SenseVoiceSmall 输出完整句并补充标点；已登记姓名作为热词上下文。电脑端不可用时会切回本地中英双模型。
- 说话人归属综合人脸、嘴部活动和本地声纹；检测到同步嘴部活动时，电脑端 SepFormer 分成两路并发调用两个 FunASR 会话，手机端逐路匹配声纹与嘴部活动，未登记者使用陌生人编号。
- 内置单人英语、双人同步发言、脸部遮挡、出画与重入视频/WAV；固定素材由 Part B/C 回放、同源语音对照和边界诊断按钮使用。
- 摄像头使用 `FIT_CENTER` 显示完整预览画面；点按已保存转写可编辑并覆盖文字。

复现实验的检测数量、嘴部匹配、端云流量和转写结果记录在应用私有 CSV，可由 UI 导出。面向 VIVO X200 Pro 的 APK 用 `-PtargetAbis=arm64-v8a` 构建；模拟器验证包用 `-PtargetAbis=x86_64`。

## 重要文件

| 位置 | 职责 |
| --- | --- |
| `app/src/main/java/edu/scut/livetranscript/MainActivity.kt` | 界面、权限、CameraX、AudioRecord、识别引擎切换、结果保存 |
| `FacePipeline.kt` / `FaceTracker.kt` | YuNet/SFace 人脸检测、特征提取、跨帧跟踪与身份匹配 |
| `FaceOverlayView.kt` / `ImageViewportTransform.kt` | 预览框、标签和坐标映射 |
| `MouthActivityAnalyzer.kt` / `MouthTrackMatcher.kt` / `MouthMotionScore.kt` | MediaPipe 嘴部分析、人脸轨道匹配与 `jawOpen` 活动分数 |
| `SpeakerAttributor.kt` | 将语音时间窗与嘴部活动关联；证据不足返回空 |
| `FunAsrSpeechEngine.kt` | FunASR WebSocket 协议、临时与最终文本事件 |
| `TranscriptSession.kt` | 临时文本与最终文本去重、尾字保留 |
| `LocalStore.kt` | SQLite 身份、重命名/删除、转写历史与字幕文字修订 |
| `app/src/main/res/xml/network_security_config.xml` | WebSocket 明文地址白名单 |
| `docs/experiments/mec-partitioning.md` | Part B/C 实验矩阵、性能指标与边界回放 |
| `testdata/reproducible-video/README.md` | 固定素材来源、变换步骤与 SHA-256 |

## 构建、测试、安装

用实际安装目录替换以下示例路径：

```powershell
$env:JAVA_HOME = 'D:\JDK17'
$androidSdk = 'C:\Program Files (x86)\Android\android-sdk'
$env:ANDROID_HOME = $androidSdk
$env:ANDROID_SDK_ROOT = $androidSdk
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PtargetAbis=x86_64
```

VIVO X200 Pro APK：`.\gradlew.bat :app:assembleDebug -PtargetAbis=arm64-v8a`，输出仍为 `app\build\outputs\apk\debug\app-debug.apk`。

联想模拟器已经启动时：

```powershell
$adb = Join-Path $androidSdk 'platform-tools\adb.exe'
& $adb devices -l
& $adb reverse tcp:10095 tcp:10095
& $adb install --no-streaming -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb shell monkey -p edu.scut.livetranscript 1
```

电脑端三项服务可由 `server\start_local_services.ps1` 启动、`server\stop_local_services.ps1` 关闭；服务日志在 `server\artifacts\logs`。首次运行加载 FunASR 与 SepFormer 模型需要等待各自端口开始监听。

`adb reverse` 让模拟器的默认 `localhost:10095` 访问开发电脑上的 FunASR 服务。模拟器重启后需重新执行。联想模拟器的摄像头/麦克风开关由其 UI 管理；先查看应用中的画面和 dBFS 再判断链路是否正常。

`-r` 会保留 SQLite 身份库和转写历史。除非用户明确要求，**不要**执行 `adb uninstall`、`pm clear` 或删除应用数据。

## 验收与排查

1. 获得参与者同意后开始采集，确认镜头中至少一张脸有框和标签。
2. 登记一张脸；停止后测试重命名和删除重复身份，确认历史转写保留。
3. 运行同源英文 WAV 对照，核对本地中英、英文 Vosk 与电脑 FunASR 的识别文本、字幕时延和端云流量。
4. 断开服务，确认状态切换为本地中英后备；检查中文、英文与混说样例的文本。
5. 运行双人同步发言 WAV：核对 GPU SepFormer 两路分离、两条并行 FunASR 文本、声纹/嘴部映射和陌生人 A/B。运行边界视频，检查遮挡恢复、出画消失、重入后轨迹标签。
6. 停止采集，确认状态不再显示摄像头/麦克风工作，历史中每段最终转写只保存一条。

常用日志命令：

```powershell
& $adb logcat -d | Select-String 'FunASR|AudioRecord|AudioFlinger|FATAL EXCEPTION|livetranscript'
```

## 继续开发时的约束

- 只在最终识别结果写入 SQLite；临时结果只更新界面。
- 音频由 `MainActivity` 单独采集，识别引擎只消费 PCM 数据；不要让多个组件同时打开麦克风。
- 面向多人场景时，宁可返回“未确认说话人”，也不要用历史说话人标签覆盖当段视觉证据。
- `MouthMotionScore` 当前只使用 `jawOpen` 差值，目的是避免面部其他 blendshape 在遮挡时产生伪活动。调整阈值必须同时覆盖单人、两人轮流、静默和遮挡测试。
- FunASR 的服务端没有通用的 segment ID；当 `SpeechEvent` 没有可靠音频来源时间，不能强行归属说话人。
- 不要提交 APK、Gradle 缓存、数据库、录音、参与者数据、个人路径或服务密钥。

## 给下一位 Agent 的任务提示

```text
请接手 live-face-transcript Android 项目。先阅读 AGENT_HANDOFF.md、README.md、CONTRIBUTING.md、ROADMAP.md 和 git status；以当前工作树为准，不丢弃已有改动。默认语音路径是电脑端 FunASR 中英识别，断连后回退本地中英双模型 Vosk。项目包含端云静态/动态人脸切分、声纹/嘴部归属、双人重叠分离及遮挡/出画固定回放素材。

先设置本机 JDK 17 与 Android SDK，运行 .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug。若联想模拟器在运行，使用 adb reverse tcp:10095 tcp:10095 和 adb install --no-streaming -r 安装 APK，保留已有应用数据。只有在参与者同意后才启动摄像头和麦克风。

验证人脸框、身份登记和管理、中文/英文/中英混说、FunASR 断开后的本地双语后备、静态与动态切分、双人同时发言的并行识别、声纹和嘴部归属，以及遮挡和出画重入。修改后更新 README、交接文档和实验记录说明，运行相关测试并记录输入素材、耗时和端云字节。
```
