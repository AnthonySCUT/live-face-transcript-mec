# 小组开发指南

## 首次加入

1. 将 GitHub 用户名发给仓库所有者，由所有者邀请为 Collaborator，接受邀请后克隆私有仓库。
2. 按 README 配置 JDK、Android SDK 和模拟器，或直接使用 Android 实体机。
3. 阅读 `AGENT_HANDOFF.md`，向 Agent 提供工程根目录；仓库克隆目录名称可以不同，以包含 `settings.gradle` 的目录为准。
4. 首次运行按 README 完成人工功能验收，记录设备、系统版本和未解决问题。

## 日常流程

```powershell
git switch main
git pull --ff-only
git switch -c feature/your-feature
# 完成修改后，只添加与本次任务有关的文件。
git add <文件路径>
git commit -m "说明改动目的"
git push -u origin feature/your-feature
```

在 GitHub 发起 Pull Request，请另一位组员审阅后再合并。`fix/` 用于修复，`docs/` 用于文档。不要直接覆盖其他组员分支或强推 `main`。

## 分工建议

| 工作方向 | 主要位置 | 交付内容 |
| --- | --- | --- |
| 视频与身份 | FacePipeline、FaceTracker、FaceOverlayView | 跟踪稳定性、身份匹配与性能记录 |
| 音频与界面 | MainActivity、FunAsrSpeechEngine、TranscriptSession | 录音生命周期、中英混合实时转写、Vosk 后备与最终文本保存 |
| 视觉说话人归属 | MouthActivityAnalyzer、SpeakerAttributor | 嘴部活动采样、轨道匹配与保守归属策略 |
| 数据管理 | LocalStore | 数据结构版本、导出和清理功能 |
| MEC 实验 | 参见 ROADMAP | 服务端、静态/动态切分、数据分析 |

MainActivity 目前职责较多；多人并行修改前先在 Issue 中约定范围，后续可逐步拆出摄像头和音频控制组件。

## 每次 PR 的人工验收

- 按实际改动运行构建，并写明是否成功；没有执行的检查明确注明。
- 涉及采集时，确认权限拒绝、停止、返回后台后资源释放正常。
- 涉及人脸时，验证登记身份、陌生人编号、多人框选和重启后的登记库恢复。
- 涉及音频时，验证真实输入电平、FunASR 中英混合转写、服务断开后的 Vosk 中文后备和历史记录。
- 涉及说话人归属时，验证单人、两人轮流、静默和嘴部遮挡；证据不足时应显示“未确认说话人”。
- 涉及存储时，确认升级保留已有数据；删除数据必须由用户明确触发。
- 模拟器验收记录不能代替真实手机的时延与能耗实验结果。

不提交个人路径、缓存、APK、密钥、录音、数据库或参与者个人测试数据。当前源码没有额外授予开源许可证，第三方模型遵守其各自许可。
