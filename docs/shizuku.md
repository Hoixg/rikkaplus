# Shizuku

入口：**扩展管理 → Shizuku**，与 Termux 独立。页面显示应用安装、服务运行与授权状态，可打开 Shizuku、下载安装及申请授权；返回应用、服务连接变化及授权结果会刷新状态。

在**助手 → 本地工具 → Shizuku**打开工具开关。开关默认关闭；打开时，若服务已经运行，会请求 Shizuku 授权。AI 工具本身不会弹授权窗口。

**扩展管理 → Shizuku → 工具权限 → 执行前审批**默认开启。执行命令、取消任务及清理任务输出走普通工具审批；状态诊断、任务列表、任务状态和输出读取不重复审批。关闭后，已启用 Shizuku 工具的聊天和定时任务可直接执行命令。此设置通过现有 Settings/DataStore 保存，在每次审批判断时读取最新值；Shizuku 服务运行及系统授权仍是执行前提。

## 实现来源

按用户要求，直接沿用 [ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent) 的实现，固定参考提交为 [`a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc`](https://github.com/ExTV/rikkahub-agent/commit/a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc)。来源项目与本项目均使用 AGPL-3.0，保留来源标注。

迁入范围：

- `shizuku/ShizukuManager.kt`、`ShizukuStatus.kt`、`ShizukuUserService.kt`、`ShizukuCommandRunner.kt`。
- `IShizukuUserService.aidl`、`ShizukuTool.kt`、独立授权页面和对应中英文资源。
- 原 SSH 工具中执行层依赖的 `BoundedOutputStream`，单独提取，不迁入整套 SSH 工具。
- 状态映射、绑定错误、返回值解析、命令执行及输出截断测试。

本分支适配：扩展管理导航、现有 Toast API、现有工具审批接口，以及反射创建的 UserService 的 `@Keep` 保留标记。不引入 Agent 的任务数据库、审批框架或其他设备工具。

执行层使用 [Shizuku 官方 API](https://github.com/RikkaApps/Shizuku-API) 13.1.5 的 `bindUserService`，复用连接，服务为非守护模式。使用运行中的 Shizuku 服务身份；ADB 启动通常为 shell UID，root 启动则可能为 root。授权不能突破该身份的系统与 SELinux 限制。

## 工具

| 工具 | 功能 |
| --- | --- |
| `shizuku_exec` | 同步执行或以 `background=true` 启动后台任务，返回 `task_id` / `output_id`。 |
| `shizuku_task_read` | 查询状态、退出码和完成时间；`wait_ms` 可等待最多 20 秒，停止等待不取消任务。 |
| `shizuku_task_list` | 查看所有聊天共用的任务以及容量和保留限制，不展示命令、环境变量或 stdin。 |
| `shizuku_task_cancel` | 取消指定任务，停止其独立进程组；返回 `stopping` 时应继续查询直至 `finished=true`。 |
| `shizuku_task_forget` | 删除已结束任务及其内存输出；运行中的任务必须先取消并等待结束。 |
| `shizuku_output_read` | 选择 stdout/stderr，按字节分页读取或按字面文本搜索已有输出，无需重新执行。 |
| `shizuku_status` | 检查安装、Binder、授权、服务版本/UID、shell/root 身份、Android 信息及实际 UserService 连通性。 |

## 执行参数和任务生命周期

- `command` 与绝对路径的 `executable` 二选一。后者配合 `arguments` 使用，参数直接传递，不经过 shell 字符串拼接。
- 支持可选的 UTF-8 `stdin`、绝对路径 `working_dir` 和仅对本次进程生效的 `environment`。stdin 写完即关闭；省略时直接 EOF。不提供交互终端；Termux 的会话工具保持独立。
- `timeout_ms` 默认 30 秒，范围 1～300 秒；同步与后台任务都执行此时间限制。后台任务使用 `background=true`，立即返回任务 ID。
- 同步命令随聊天生成取消；后台任务通过取消工具显式停止。读取超时或取消读取不会停止任务。
- 命令通过 `/system/bin/setsid` 建立独立进程组，结束、超时或取消时清理本组普通子进程。主动建立新会话脱离该组的守护进程不保证可取消。缺少 setsid 的 ROM 会明确报错，诊断工具会报告不支持。
- 最多同时运行 4 个任务，保留最多 16 个任务。已结束任务保留 1 小时，后续访问时清理过期记录；容量已满时需先清理已结束任务，不会静默删除近期输出。
- 任务和输出只在 UserService 内存中保存，无持久日志或任务数据库。UserService 重启后无法继续追踪；服务被强制杀死时，子进程清理不保证完成。
- 客户端在派发前生成任务 ID，相同 ID 不重复启动。连接或返回值异常时，执行结果记为未知，应先查看任务列表/状态，不能自动重放可能已执行的命令。重连只移除旧连接回调，避免终止其他聊天的任务。

## 输出读取

- 同步结果仍预览 stdout 前 8,000 字节、stderr 前 2,000 字节，附带截断状态。超时包含部分输出和任务 ID。
- 每个任务的每条流最多保留 1 MiB。超出容量时 `output_truncated` 和 `dropped_bytes` 明确说明丢失量；输出分页并不意味着无限保留。
- `shizuku_output_read` 使用 `task_id`、`stream`（默认 stdout）、`offset`（默认 0）、`limit`（4～65,536 字节，默认 8,000）。使用返回的 `next_offset` 读取下一页，游标只越过已返回字节；UTF-8 字符不会因分页被拆开。
- `query` 是最多 1,024 字符的字面搜索文本。运行中的任务若暂未输出完整字符或匹配文本，可使用返回的偏移继续读取。
- 不再经由工作区的共享 `/tool_outputs` 二次落盘，工具结果仍按普通聊天消息保存。

## 连接边界

- 服务未安装、未运行、未授权、绑定失败及超时均返回结构化错误。不会自动改用其他权限后端。
- 某些 ROM 上 UserService 可能启动失败，Agent 也记录过绑定超时；服务版本与管理器不一致时，应先重启 Shizuku 再检查授权。
- UserService 协议版本包含在绑定版本号中，升级接口时会替换旧服务，避免应用 versionCode 不变时继续运行旧实现。

需要在运行 Shizuku 的手机上验证实际授权弹窗、撤销、服务重启和命令执行。主机 JVM 测试不能证明设备侧连接可用。

## 本地验证（2026-10-24）

- GitHub Debug Kotlin/AIDL 编译和 GitHub Release 资源处理通过。
- 36 项迁入的 Shizuku/输出截断测试、24 项现有 Termux/设置映射测试通过，共 60 项，无失败或跳过。执行测试通过本机 Git 自带的 `sh` 启动真实子进程。
- Debug/Release 合并清单中的 Provider authority、跨用户访问保护及 SDK metadata 均核对通过；中英文资源无重复键。
- 未执行手机上的 Shizuku 授权与特权命令验证。

## 增强验证（2026-10-25）

- GitHub Debug Kotlin/AIDL 编译通过；本次 Shizuku 实现文件的定向格式化通过。
- 60 项 Shizuku、任务生命周期、客户端取消、请求验证、输出分页和截断测试，以及 51 项 Termux 回归测试通过，共 111 项，无失败、错误或跳过。
- 新增验证覆盖后台任务、同 ID 去重、单任务取消隔离、同步取消派发、stdin/目录/环境变量、非零退出、部分输出、UTF-8 分页与跨写入搜索、容量限制和过期清理。
- 本机无连接的 Android 设备，授权、ROM 上的 UserService 连接、实际进程组清理和服务重启仍需手机验证；未构建 APK。
