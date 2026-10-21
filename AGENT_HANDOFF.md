# 实时人脸与语音记录：Agent 交接指南

把整个仓库根目录交给下一位 Agent。先阅读本文件、[README.md](README.md)、[CONTRIBUTING.md](CONTRIBUTING.md) 和当前 `git status`，再修改代码。不要假设仓库所在磁盘或模拟器端口固定。

## 当前项目状态

- Android application ID：`edu.scut.livetranscript`。
- 最低 API 26，目标/编译 API 35，JDK 17，Gradle 8.9。
- 已实现本机多脸检测、SFace 身份匹配、会话内陌生人编号、身份重命名/删除、本机 SQLite 转写历史。
- 默认语音主链路是 `ws://localhost:10095` 上的 FunASR 两遍流式识别，支持已实测的中英混合转写；连接失败自动切换 Vosk 本地中文后备。
- 说话人归属依据可见人脸的嘴部开合变化，无法证实时标记“未确认说话人”。未实现声纹、音色辅助或多人同时发言分离。
- 摄像头使用 `FIT_CENTER` 显示完整预览画面；转写历史在界面中可滚动查看。

实验目标中的约 2 秒时延尚未以正式实验数据证明。模拟器仅用于功能测试，真机仍需复核性能、摄像头和麦克风表现。

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
| `LocalStore.kt` | SQLite 身份、重命名/删除与转写历史 |
| `app/src/main/res/xml/network_security_config.xml` | WebSocket 明文地址白名单 |
| `docs/experiments/live-transcription-protocol.md` | 可复现实验流程和记录表 |

## 构建、测试、安装

用实际安装目录替换以下示例路径：

```powershell
$env:JAVA_HOME = 'D:\JDK17'
$androidSdk = 'C:\Program Files (x86)\Android\android-sdk'
$env:ANDROID_HOME = $androidSdk
$env:ANDROID_SDK_ROOT = $androidSdk
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK：`app\build\outputs\apk\debug\app-debug.apk`。

联想模拟器已经启动时：

```powershell
$adb = Join-Path $androidSdk 'platform-tools\adb.exe'
& $adb devices -l
& $adb reverse tcp:10095 tcp:10095
& $adb install --no-streaming -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb shell monkey -p edu.scut.livetranscript 1
```

`adb reverse` 让模拟器的默认 `localhost:10095` 访问开发电脑上的 FunASR 服务。模拟器重启后需重新执行。联想模拟器的摄像头/麦克风开关由其 UI 管理；先查看应用中的画面和 dBFS 再判断链路是否正常。

`-r` 会保留 SQLite 身份库和转写历史。除非用户明确要求，**不要**执行 `adb uninstall`、`pm clear` 或删除应用数据。

## 验收与排查

1. 获得参与者同意后开始采集，确认镜头中至少一张脸有框和标签。
2. 登记一张脸；停止后测试重命名和删除重复身份，确认历史转写保留。
3. 启动 FunASR 并运行 `adb reverse`，说 `How are you?` 和 `这个苹果 how much。`，确认状态为 FunASR 中英混合识别且文字包含正确英文。
4. 断开服务，确认状态切换为本地中文后备；不要把该后备当作英文或中英混合能力。
5. 两人同时入镜且轮流说话，检查嘴部有活动的人被优先关联。嘴部遮挡、画外发声、两人同时说话或活动接近时，“未确认说话人”是正确的保守结果。
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
请接手 live-face-transcript Android 项目。先阅读 AGENT_HANDOFF.md、README.md、CONTRIBUTING.md、ROADMAP.md 和 git status；以当前工作树为准，不丢弃已有改动。项目已实现本机人脸识别、FunASR 中英混合流式转写（Vosk 中文后备）、本机历史和基于可见嘴部运动的说话人归属。

先设置本机 JDK 17 与 Android SDK，运行 .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug。若联想模拟器在运行，使用 adb reverse tcp:10095 tcp:10095 和 adb install --no-streaming -r 安装 APK，保留已有应用数据。只有在参与者同意后才启动摄像头和麦克风。

验证人脸框、身份登记和管理、中文/英文/中英混说、FunASR 断开后的 Vosk 后备、两人轮流发言的视觉归属和停止释放资源。不要把未验证的时延或声纹能力写成已实现；对于遮挡或多人同时发言，应允许“未确认说话人”。修改后更新 README、交接文档和实验记录说明，运行相应测试并如实报告结果。
```
