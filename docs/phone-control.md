# 手机操作（无障碍）

实现复用 [RikkaHub Agent 的无障碍服务和工具](https://github.com/ExTV/rikkahub-agent/tree/a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc/app/src/main/java/me/rerere/rikkahub/data/ai/tools/local)。具体来源和适配范围见 [源码来源](phone-control-sources.md)。

## 开启

在「扩展管理 → 手机操作」进入系统设置启用 RikkaPlus 无障碍服务，打开「允许 AI 操作手机」，并在助手本地工具中启用「手机操作」。原有设置继续保存在现有 DataStore 的 `PhoneControlConfig`，没有新增数据库、数据表或迁移。

「操作前审批」继续使用现有 Agent 的工具审批。开启时，点击、输入、滑动、滚动、系统导航及打开应用需要批准；读屏、截图、查询节点与应用列表不单独审批。关闭审批后可以连续操作；关闭 AI 总开关后，已创建的工具也停止返回界面数据和执行后续动作。

## 工具接口

沿用参考项目的接口，不保留旧的 `phone_screen` / `phone_action` 快照协议。

| 工具 | 用途与参数 |
| --- | --- |
| `phone_status` | 当前 App 的接入诊断：AI 开关、系统授权、服务连接、锁屏及截图支持。 |
| `read_window_tree` | 读取当前窗口；`max_nodes` 默认 500、最高 2000，`verbose` 可显示未过滤节点。 |
| `find_node` | 按 `by=text/content_description/view_id_resource_name` 与 `value` 查找当前节点。 |
| `click_node` | 使用当前 `node_id`，或直接传 `by/value/nth`；按参考实现寻找可点击祖先。 |
| `set_text` | 使用节点或选择器设置 `text`，空字符串清空；执行 Android 原生文本替换。 |
| `tap` / `long_press` | 直接使用显示屏绝对像素 `x/y`，长按可设 `duration_ms`。 |
| `swipe` | `start_x/start_y/end_x/end_y` 与可选 `duration_ms`。 |
| `scroll` | `direction=up/down/left/right`；可用 `x/y` 指定容器，无容器或不支持方向时使用参考实现的滑动方式。 |
| `global_action` | 返回、主页、最近任务、通知、快捷设置、锁屏和电源菜单。 |
| `take_screenshot` | 直接截图并返回聊天图片附件；可选 `display_id`，默认 0。 |
| `list_installed_apps` / `launch_app` | 列出系统允许当前 App 看见的应用，或通过 `package_name` 打开应用。 |

## 行为

节点编号采用参考实现的 `windowId:traversalIndex`，按当前窗口实时解析，也可以完全不使用编号，直接按文字或资源 ID 查找。没有 `snapshot_id`、会话快照缓存、TTL、一次性消费或全页一致性前置校验。文字、焦点或勾选状态变化不会禁止整页坐标操作。

截图直接调用无障碍截图接口，不先读取或比较控件树。没有节点、聊天正在生成文字、滚动或页面动画都不会使一张已成功取得的图片失效。截图支持 Android 11 及以上；Android 的限频、受保护画面等错误按照参考实现返回明确原因，不添加自动重截图循环。图片通过现有 FilesManager 保存为聊天附件，不额外写入相册。

动作结果沿用参考实现的 `after` 信息，包括当前应用、显示屏尺寸、键盘和通知栏状态，以及用于提示界面变化的 `screen_changed`。这里的树哈希只计算变化提示，不作为拒绝动作的条件。没有“输入回读不一致就覆盖原生执行结果”的二次判定。

保留 Android 系统授权、用户开启的操作审批和手势串行执行。节点操作只处理当前系统窗口；`package_name` 是参考接口的可选前台应用检查。系统不暴露的节点、无有效输入控件、超出屏幕的坐标或无障碍服务断开，都会直接返回对应原因。

审批切回本应用时，接入适配层会尝试恢复审批前的目标应用；没有启动入口的系统界面需要用户先切回，或关闭逐次审批后连续操作。不会自动唤醒或解锁手机。

## 存储与验证

聊天记录、工具结果及图片继续走现有聊天与文件管理流程，服务不再额外维护无人读取的内存操作日志。没有新数据库、Room 实体、DAO、表结构、迁移或后台工作流。

移植参考项目的参数校验、离线错误、节点编号、滚动和等待测试，并验证本 App 的开关、审批与附件接线。没有连接 Android 设备时，不将这些 JVM 检查当作真机读屏、截图或手势验证。
