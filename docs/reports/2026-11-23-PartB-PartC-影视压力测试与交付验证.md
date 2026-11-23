## 核心改动

- 保留同一段干净英语语音作为 Part A 基线和端云对照输入；本地中英 Vosk、本地英文 Vosk、电脑端 FunASR 均使用该音频前 20 秒，直接比较同一输入。
- 增加 1940 年电影《His Girl Friday》预告片压力输入：53–73 秒快剪对白、47–49 秒新闻编辑室多人镜头，以及两张脸在 5–10 秒被遮挡的版本。素材来源、复现脚本和 SHA-256 位于 `testdata/reproducible-video/`。
- 为小脸增加嘴部分析补充路径：全帧关键点未关联的轨迹按检测框裁切并放大，再按轨迹 ID 更新嘴部活动样本。
- 保留受控双人重叠 PCM 作为并行分离转写输入；证据不足时字幕使用“陌生人 A/B”并保留“人脸关联待确认”。

## 代码变更

- `app/src/main/java/edu/scut/livetranscript/MainActivity.kt`：边界回放增加电影快剪、多人镜头和遮挡素材；快剪每 500 ms 采样，记录真实帧宽高；语音对照统一为同源 20 秒。
- `app/src/main/java/edu/scut/livetranscript/MouthActivityAnalyzer.kt`：全帧 MediaPipe 分析未匹配的轨迹使用 224×224 局部脸部裁切；以独立位图副本管理 MediaPipe 图像生命周期。
- `testdata/reproducible-video/build_film_stress_inputs.py`：从固定来源时间戳生成三段 640×360、10 fps、20 秒 MP4。
- `testdata/reproducible-video/README.md`：记录视频来源、场景条件、再生成命令、音频/视频实验边界与素材哈希。
- 回放 CSV：`artifacts/reproducible-experiments-final-run.csv`、`artifacts/reproducible-edge-diagnostics-final-run.csv`。

## 验证结果

- Android 单元测试：75 项通过，0 失败。人脸 MEC 服务测试 4 项通过；SepFormer/并行 FunASR 服务测试 4 项通过。`8000/health` 与 CUDA overlap 服务 `8001/health` 返回正常。
- Part B 静态矩阵本轮记录 36 行，覆盖本地、对齐后卸载、整帧卸载，320/640 输入与 0/512 kbps。各条件每项 3 帧。

  | 切分方式 | 输入 | 0 kbps 中位时延 | 512 kbps 中位时延 |
  | --- | ---: | ---: | ---: |
  | 本地 | 320 | 32.5 ms | 47.1 ms |
  | 本地 | 640 | 44.9 ms | 34.5 ms |
  | 对齐后卸载 | 320 | 539.6 ms | 557.0 ms |
  | 对齐后卸载 | 640 | 555.7 ms | 540.0 ms |
  | 整帧卸载 | 320 | 525.7 ms | 525.8 ms |
  | 整帧卸载 | 640 | 524.5 ms | 655.9 ms |

- Part C 动态回放本轮记录 36 行，按 0→512→0 kbps 执行。不限速阶段 24 帧中 23 帧选择本地、1 帧选择对齐后卸载，累计上传 22,936 B、下载 2,658 B；512 kbps 阶段 12 帧选择本地，传输字节为 0。
- 英语语音对照三种路径均返回英文字幕。20 秒本地中英默认耗时 22.09 s，本地英文 Vosk 21.07 s，电脑端 FunASR 26.17 s；FunASR 传输 665,861 B 上行、2,072 B 下行，最终文本更完整。
- SepFormer 双人重叠回放在 CUDA 上返回两路有效转写，上传 640,000 B、返回 1,707,140 B；嘴部候选轨迹为 2。两路声源以陌生人 A/B 保存，字幕记录人脸关联待确认。
- 模拟器电影边界回放本轮写入 91 行：快剪 39 帧中 37 帧得到嘴部轨迹关联，检测到 1–5 张脸；新闻编辑室场景 10/10 帧检测到 4 张脸并得到嘴部轨迹；遮挡版未遮挡阶段检测 4 张脸，遮挡阶段降为 1 张，遮挡解除后恢复为 4 张。双人重叠、整脸遮挡、出画重入素材同时写入诊断日志。
- 最终 VIVO X200 Pro 安装包仅包含 `arm64-v8a` 原生库，APK 签名验证通过；x86_64 模拟器包也已签名并安装运行。当前 ADB 连接为 `emulator-5554`。

  | 安装包 | 大小 | SHA-256 |
  | --- | ---: | --- |
  | `artifacts/LiveFaceTranscript-VIVO-X200-Pro-arm64.apk` | 190,649,928 B | `66E049F8FB92DFB691F853126F4E68AB2D4F0B09448E52ACFB8CF56776C02A33` |
  | `artifacts/LiveFaceTranscript-AVD-x86_64-final.apk` | 227,707,923 B | `7D35CC9189CCFA69BE672516F70C4D514524BBB216FB7D876C9F4C83252F6161` |
