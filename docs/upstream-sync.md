# 1.0 分支同步上游约定

`1.0` 是 RikkaPlus 的开发分支。同步上游时合并通用改进，并保留下列定制约定；不要将上游新增文件视为默认应恢复的功能。

## 已移除：内置 Web 服务器

此功能已明确删除，后续同步不得重新引入。删除范围包括：

- `web/`：嵌入式服务器模块及其 Gradle 配置。
- `web-ui/`：配套 Web 前端、依赖、锁文件及打包配置。
- `app/src/main/java/me/rerere/rikkahub/web/` 和对应的 `app/src/test/java/me/rerere/rikkahub/web/`：服务器 API、JWT 等代码及测试。
- 服务器管理器、前台服务、启动调用、Koin 注入、设置入口、导航路由及服务器通知。
- 对服务器模块的项目依赖，以及专用于此功能的 Ktor Server、JWT、JmDNS 声明和服务端资源。
- 服务器专用文案，例如 `setting_page_web_server*`、`notification_channel_web_server`、`notification_web_server*`。

处理合并时注意：

1. 上述路径出现“本地已删除、上游修改”冲突时，保留删除。
2. 上游新加的服务器文件通常不会产生冲突，例如 `WebJwt.kt`、其测试或前端组件，必须检查并删除。
3. 新语言的 `strings.xml` 可能整份带回已移除的服务器文案，需要通过仓库 `i18n-localization` 工具清理对应键。
4. 合并 `RikkaHubApp.kt`、依赖注入及版本目录时，保留模型目录更新、备份恢复等通用逻辑，去掉服务器启动和依赖。

应用内 WebView、HTML/Markdown 预览、联网搜索、WebDAV 备份以及 MCP/SSH/Termux 的客户端网络访问仍需保留。
`ACCESS_LOCAL_NETWORK` 等共享网络权限不等于内置 Web 服务器，不按名称批量删除。

订阅账号登录还需要 `oauth/src/main/java/me/rerere/oauth/OAuthLoopbackCallbackServer.kt` 及
`oauth/build.gradle.kts` 使用的 Ktor Server Core/CIO。该回调接收器只绑定 `127.0.0.1`，在授权期间临时接收登录结果，
属于必须保留的登录组件。因此版本目录中的 `ktor-server-core`、`ktor-server-cio` 是明确例外；服务器管理界面、
Web API、Web 前端、JWT 及 JmDNS 不在此例外中。

## 提交前检查

下面的命令应无输出，确认没有重新跟踪服务器目录：

```powershell
git ls-files -- web web-ui app/src/main/java/me/rerere/rikkahub/web app/src/test/java/me/rerere/rikkahub/web
```

下面的搜索也应无匹配；`rg` 无匹配时返回 1 属于正常情况：

```powershell
rg -n 'WebServerManager|startWebServerIfEnabled|WebApiModule|WebJwt|io\.ktor\.server|org\.jmdns' app/src/main/java app/src/test
rg -n 'web_server|permission_local_network' app/src/main/res -g strings.xml
rg -n 'libs\.ktor\.server|jmdns|project\(":web"\)|include\(":web"\)' app/build.gradle.kts settings.gradle.kts
rg -n 'ktor-server-auth|ktor-server-auth-jwt|jmdns|auth0' gradle/libs.versions.toml
```

随后检查合并冲突标记，运行必要的编译和针对受影响功能的测试。只有用户要求时才构建安装包。

## 其他必须保留的定制

- 对外品牌名统一为 `RikkaPlus`，包括各语言应用名、关于与分享、OAuth 回调页、导出名称和请求头；项目入口以 `Hoixg/rikkaplus` 为准。保留上游来源署名、真实第三方服务名称及用于兼容既有数据的内部标识。设置页已删除 QQ 群与 Discord 社区入口，同步时不恢复。
- 赞助功能已移除，包括设置入口、启动次数提醒、赞助名单 API、付款链接、专用配置与资源、GitHub Funding 配置；同步时不恢复。
- 包名 `io.github.hoixg.rikkaplus`，Debug 使用 `.debug` 后缀；源码命名空间与包名分开维护。
- Release 只读取 `signing-1.0.properties`；云端使用 `KEY_BASE64_1_0` / `SIGNING_CONFIG_1_0`，不得回退到旧 `custom` 签名。
- 每次构建只产出一份含 ARM64 和 x86_64 的 GitHub 通用 APK：默认 Release，只有明确要求时构建 Debug；不附带构建其他类型、分架构 APK 或 Play AAB。`buildAll` 同样只构建 Release 通用包。保留单 APK、架构及发布附件数量校验；每日构建清理 `1.0-nightly` 遗留的其他 APK，不修改历史正式版本附件。
- OAuth 的 Ktor 回调接收器依赖 Kotlin 反射库，不得全局排除 `kotlin-reflect` 或用忽略缺失类的规则掩盖依赖问题。
- 多 Key 手动选择及模型绑定、订阅账号登录、MCP 扩展入口和审批、Shizuku 授权与命令工具、Termux 会话与后台任务（跨聊天共享）、工作区文件备份、聊天页和工作区交互定制。
- 已移除的内置中转商预设及仓库定位介绍，不随上游恢复。
