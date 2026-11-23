## 核心改动

- Windows 桌面连接助手通过 USB ADB 反向转发 `8000/8001/10095`，启动或复用电脑端服务，按 AVD/手机 ABI 安装 APK 并打开应用。手机端自动配置本机地址并检测人脸、FunASR 和双人分离服务。
- Part A/B/C 回放在受限 AVD 上重跑：2 个 guest CPU 核、2048 MB RAM、SwiftShader 软件图形渲染。
- 同步核对 CPU/GPU/NPU 与频率后端，并将模拟器实际暴露的频率驱动信息写入实验边界说明。

## 代码变更

- `ConnectPhoneAssistant.ps1`、`ConnectPhoneWorker.ps1`、`打开手机连接助手.bat`：桌面按钮、服务管理、USB 转发、按 ABI 安装和启动。
- `HostConnectionConfig.kt`：模拟器使用 `10.0.2.2`，实体手机使用 USB reverse 对应的 `127.0.0.1`；三路连接状态在应用主界面显示。
- `scripts/Start-MobileProfileEmulator.ps1`：复现 2 核、2048 MB 和 SwiftShader profile；`scripts/collect_latest_emulator_replay.py`：从模拟器提取本轮最新回放行。
- `artifacts/mobile-profile-latest-run.csv`：本轮 72 行 Part B/C 数据。

## 验证结果

- Android 单元测试 77 项通过，0 失败；ARM64 与 x86_64 APK 均已构建并签名。
- AVD guest 报告 2 CPU、`MemTotal=2019400 kB`；SurfaceFlinger 报告 Google SwiftShader。NNAPI 注册为 `nnapi-sample_*` 服务。CPU frequency driver 为 `dummy`，min/max 字段分别为 `1/2`，本轮按模拟器报告值留档。
- 本地推理代码使用 OpenCV YuNet/SFace、MediaPipe 默认 CPU delegate 与 Vosk；实时摄像头帧间隔为身份分析 450 ms、嘴部分析 200 ms。
- USB 连接助手在 AVD 上完成安装和启动；应用显示人脸 MEC、FunASR、双人分离均已连接。电脑端 `8000/8001` 健康检查正常，FunASR 端口 `10095` 与 ADB reverse 正常。
- 本轮静态 640 输入、本地、0 kbps 的中位时延为 35.03 ms（3 帧）；动态不限速阶段为 29.33 ms（24 帧），512 kbps 阶段为 27.42 ms（12 帧）。动态共选择本地 35 帧、对齐后卸载 1 帧、整帧卸载 0 帧；该次对齐卸载约 534.85 ms。动态合计上行 22,936 B、下行 2,658 B。
- 本轮动态策略以本地计算为主，观测中位时延低于本轮静态本地基线；每轮具体切分由应用剖析数据和网络探测决定。VIVO X200 Pro 的 ARM64 APK 已就绪，手机 GPU/NPU 与 CPU 时钟数据由实机运行同一回放流程采集。
