# Shizuku

入口：**扩展管理 → Shizuku**，与 Termux 独立。页面显示应用安装、服务运行与授权状态，可打开 Shizuku、下载安装及申请授权；返回应用、服务连接变化及授权结果会刷新状态。

在**助手 → 本地工具 → Shizuku**打开工具开关。开关默认关闭；打开时，若服务已经运行，会请求 Shizuku 授权。AI 工具本身不会弹授权窗口。授权仅提供运行条件，每次 `shizuku_exec` 调用仍走本分支现有的普通工具审批。

## 实现来源

按用户要求，直接沿用 [ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent) 的实现，固定参考提交为 [`a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc`](https://github.com/ExTV/rikkahub-agent/commit/a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc)。来源项目与本项目均使用 AGPL-3.0，保留来源标注。

迁入范围：

- `shizuku/ShizukuManager.kt`、`ShizukuStatus.kt`、`ShizukuUserService.kt`、`ShizukuCommandRunner.kt`。
- `IShizukuUserService.aidl`、`ShizukuTool.kt`、独立授权页面和对应中英文资源。
- 原 SSH 工具中执行层依赖的 `BoundedOutputStream`，单独提取，不迁入整套 SSH 工具。
- 状态映射、绑定错误、返回值解析、命令执行及输出截断测试。

本分支适配：扩展管理导航、现有 Toast API、现有工具审批接口，以及反射创建的 UserService 的 `@Keep` 保留标记。不引入 Agent 的任务数据库、审批框架或其他设备工具。

执行层使用 [Shizuku 官方 API](https://github.com/RikkaApps/Shizuku-API) 13.1.5 的 `bindUserService`，复用连接，服务为非守护模式。使用运行中的 Shizuku 服务身份；ADB 启动通常为 shell UID，root 启动则可能为 root。授权不能突破该身份的系统与 SELinux 限制。

## 执行边界

- `shizuku_exec` 接受 `command` 和可选 `timeout_ms`；默认 30 秒，范围 1～300 秒。
- 沿用 Agent 的 stdout 8,000 字节、stderr 2,000 字节预算；超出后带截断标记，超时返回已捕获的部分输出。
- 单次命令不提供交互终端或持久聊天日志；Termux 的会话工具保持独立。
- 服务未安装、未运行、未授权、绑定失败及超时均返回结构化错误。不会自动改用其他权限后端。
- 某些 ROM 上 UserService 可能启动失败，Agent 也记录过绑定超时；服务版本与管理器不一致时，应先重启 Shizuku 再检查授权。

需要在运行 Shizuku 的手机上验证实际授权弹窗、撤销、服务重启和命令执行。主机 JVM 测试不能证明设备侧连接可用。

## 本地验证（2026-10-24）

- GitHub Debug Kotlin/AIDL 编译和 GitHub Release 资源处理通过。
- 36 项迁入的 Shizuku/输出截断测试、24 项现有 Termux/设置映射测试通过，共 60 项，无失败或跳过。执行测试通过本机 Git 自带的 `sh` 启动真实子进程。
- Debug/Release 合并清单中的 Provider authority、跨用户访问保护及 SDK metadata 均核对通过；中英文资源无重复键。
- 未执行手机上的 Shizuku 授权与特权命令验证。
