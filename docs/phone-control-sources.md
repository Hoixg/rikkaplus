# Phone Use 实现来源

## 来源与许可

- 项目：[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)。
- 功能提交：[25b70392adf7abd1c7742d46fd63c22f22a712ce](https://github.com/rikkahub/rikkahub/commit/25b70392adf7abd1c7742d46fd63c22f22a712ce)。
- 接入时的上游版本：`5b7ca774be05ea788ae59664bcff0f5486c6f39a`。
- 工具卡片显示更新来自 `ffb1c79e1f551d81a11319fa572207b221f46c95`；最近同步版本为 `64452581dc15ac7f017d6fc1ee67e1861af4bfdf`，包含截图位图使用后立即回收的改进。
- 许可：GNU AGPL v3，本仓库 [LICENSE](../LICENSE) 继续适用。

## 直接采用的实现

| 文件 | 作用 |
| --- | --- |
| `app/src/github/java/me/rerere/rikkahub/phoneuse/PhoneUseAccessibilityService.kt` | 元素读取、截图、手势、输入及应用操作。 |
| 同目录 `GestureIndicator.kt` | 无障碍覆盖层中的手势可视化。 |
| `app/src/main/java/me/rerere/rikkahub/phoneuse/PhoneController.kt` | 服务接口、连接状态及运行状态。 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/PhoneUseTool.kt` | 单一工具、元素列表、坐标换算和上游操作限制。 |
| `ChatFloatingTracker.kt`、`ChatFloatingContent.kt`、`ChatFloatingWindows.kt` | 显示当前动作、截图/手势时让位及任务结束展开。 |
| `ui/components/message/tools/PhoneUseToolUI.kt` | 聊天步骤按动作显示标题、图标与关键参数，复用现有详情展示。 |
| `app/src/github/AndroidManifest.xml` 和该渠道资源 | GitHub 专用服务声明及 Android 11+ 启用条件。 |

助手本地工具页沿用上游的悬浮窗要求、无障碍授权引导和离线说明，与本分支的 Termux、Shizuku、媒体创作等工具共同接入。采用上游 `PhoneUseToolTest` 与 `ChatFloatingTrackerTest` 验证工具和悬浮窗逻辑。

## 必要适配

1. 用户可见服务名称和启用提示改为 **RikkaPlus Phone Use**。
2. 工具通过现有依赖注入注册，截图使用现有 FilesManager 聊天附件；Phone Use 文本沿用原手机工具的输出保留策略。
3. `LocalToolOption.PhoneUse` 保留已有助手选择的 `phone_control` 序列化标识，避免解码旧助手配置时丢失其他工具、名称或提示词；运行时仅注册上游 `phone_use`，没有旧工具分支。
4. 删除 ExTV-based 旧框架、旧服务、13 个工具、专用设置字段、设置页、路由、扩展入口、渲染器、17 个文案键和配套测试。助手选择、聊天与附件沿用原存储，不新建数据库或数据表。
5. 按用户要求，支付宝（`com.eg.android.AlipayGphone`）和微信（`com.tencent.mm`）从上游的应用限制名单移除，并同步工具说明；其他名单条目和执行逻辑保持不变。

底层服务、截图和手势处理直接使用上游实现；没有额外添加快照 TTL、整页一致性校验、第三方辅助 APK、自动唤醒或新的操作限制。
