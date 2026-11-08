# 无障碍实现来源

## 来源版本与许可

- 项目：[ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent)。
- 固定提交：`a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc`。
- 许可：[GNU AGPL v3](https://github.com/ExTV/rikkahub-agent/blob/a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc/LICENSE)，本仓库的 [LICENSE](../LICENSE) 继续适用。
- 移植文件均保留来源提交和许可标记；这是现有实现的接入适配，不是另写一套快照或风控框架。

## 复用文件

上游工具目录为 `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/`，在本仓库放入 `app/src/main/java/me/rerere/rikkahub/accessibility/agent/`。

| 上游文件 | 接入范围 |
| --- | --- |
| `service/RikkaAccessibilityService.kt` | 改为原有 Android 组件名 `accessibility/PhoneAccessibilityService.kt`，复用手势分发、节点遍历、祖先定位和截图回调。 |
| `AccessibilityServiceHandle.kt` | 服务连接检查与离线错误。 |
| `WindowTreeTool.kt` | `read_window_tree`、节点编号及输出格式。 |
| `FindNodeTool.kt` | `find_node`、`click_node`、`set_text` 的实时选择器与节点解析。 |
| `TapTool.kt` / `SwipeTool.kt` | 坐标点击、长按与滑动。 |
| `ScrollTool.kt` | 容器滚动和方向正确的滑动方式。 |
| `GlobalActionTool.kt` | Android 原生系统导航。 |
| `ScreenState.kt` | 动作后的状态提示、安静等待和变化检测；变化检测不阻断动作。 |
| `ScreenshotTool.kt` | 保留 `take_screenshot` 的独立截图接口，文件保存适配现有 FilesManager。 |
| `AppLauncherTool.kt` | 仅接入 `launch_app` 与 `list_installed_apps`。 |

上游同目录对应的 `TapToolTest`、`SwipeToolTest`、`FindNodeToolTest`、`ScrollToolTest`、`GlobalActionToolTest`、`ScreenStateTest` 也一同移植；测试辅助只保留执行工具所需的函数。

## 必要适配

1. 保留已授权的无障碍组件名称和当前设置入口，复用 DataStore 中现有 AI 开关、审批设置及现有 Agent 审批。
2. 去掉原项目的工作流事件派发、AgentTurnTracker、无头会话截图推送和自动唤醒依赖。
3. 截图复用 FilesManager 的聊天附件存储，不另建相册、缓存目录或数据库。
4. 原生截图增加有界等待与取消后的图片释放；关闭硬件缓冲区及临时硬件位图。
5. 密码节点输出不回显密码，文字写入结果不回显输入内容；对外说明使用 RikkaPlus。
6. 新工具名接入现有工具卡片及结构化结果输出规则；审批返回后恢复原目标应用。
7. 移除本 App 未接入读取端的内存操作日志、重复连接状态及未调用的查询接口；工具结果和图片继续保存在现有聊天中。

没有导入上游的数据库、工作流、定时任务、Web 服务、其他系统工具或外部辅助 APK。

## 其他调研

对照过 [Operit 的 AccessibilityUITools](https://github.com/AAswordman/Operit/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/accessbility/AccessibilityUITools.kt) 与 [DSH Go 手机操作模块](https://github.com/ccch1mneyyy/dsh-Go/tree/d5ad25c5bc4c66ecacee76b3c5378db45e1db3fa/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/a11y)。本次实际移植来源为上述 RikkaHub Agent 文件；不将 DSH Go 标注为经第三方作者许可的模块复制进来。
