# 内置浏览器

## 使用入口

- 聊天页点击加号，再点「浏览器」，进入独立全屏页。
- 支持网址或搜索、前进后退、刷新或停止加载、多个标签、书签、历史、页内查找、上传和下载。
- 右上角菜单打开书签、历史、下载和浏览器设置；设置中可选择搜索引擎、桌面网站、自定义 User-Agent、恢复标签及记录历史。
- 在助手的「本地工具」中打开「浏览器」，AI 才会获得 `browser` 工具。手动浏览不依赖这个开关。
- 聊天中的浏览器工具结果可直接打开相同标签。AI 操作期间可以点击「接管」暂停后续操作，点击「继续」恢复。

自定义 User-Agent 用于替换浏览器向网站发送的设备和浏览器身份信息，通常留空即可；查看电脑版网页可直接打开「桌面网站」。

## 标签与登录状态

原生引擎使用 Android 系统 WebView，不引入额外浏览器内核、外部浏览器 APK 或远程 Playwright 服务。

标签归属当前聊天，工具的会话和工作区由 ChatToolFactory 绑定，模型不能通过参数切换到其他聊天。
界面允许用户主动查看并进入其他聊天的标签。网站 Cookie 和登录状态使用 WebView 的同一个应用配置，跨标签共用，
并不是每个聊天单独的无痕环境。网页登录、验证码及需要真实用户手势的操作由用户接管。

手动创建的标签记录不限于 3 个；AI 在当前聊天已有 3 个标签时应复用或关闭已有标签。
全局最多保留 3 个原生 WebView，优先释放不可见且未执行操作的页面；浏览器闲置 30 分钟后释放原生页面。
标签的地址和标题保留，再次进入时重新加载。网页表单、滚动位置、临时 HTML 和 JavaScript 内存不是持久会话快照。

退出浏览器界面回到聊天页，只移除网页的显示容器，不关闭标签或取消 AI 任务。正在操作的页面保持运行，
后续浏览器工具调用会恢复对应 WebView，支持用户留在聊天页时继续执行网页任务。点击「接管」会暂停后续操作，
需要点击「继续」后恢复；登录、验证码和系统权限可能仍需用户进入页面处理。这一隐藏界面运行场景尚未完成真机验证。

## AI 工具

单一 `browser` 工具通过 `action` 区分操作：

| 操作 | 作用 |
| --- | --- |
| `open` / `render` | 打开网页，或显示临时 HTML。 |
| `snapshot` / `extract` | 读取可见元素，或分页提取正文、HTML、简化 Markdown。 |
| `click` / `type` / `select_option` / `press` / `scroll` | 操作元素、表单及滚动区域。 |
| `screenshot` | 返回网页截图及截图像素、网页 CSS 视口尺寸。 |
| `wait` / `back` / `forward` / `reload` | 等待、导航或刷新。 |
| `new_tab` / `switch_tab` / `close_tab` / `list_tabs` | 管理本聊天的标签。 |
| `evaluate` / `console` | 执行页面 JavaScript（支持 Promise），查看近期控制台消息。 |
| `handle_dialog` / `upload` | 处理本标签网页对话框，或向已打开的文件选择控件提供工作区文件。 |

普通导航自动返回页面快照。元素可用快照中的 `ref` 或唯一 CSS 选择器定位，也可使用视口 CSS 坐标。
截图按最长边 1568 像素缩放，并同时返回 CSS 视口尺寸；使用截图坐标时必须按返回比例换算。
这套坐标仅用于网页 DOM，不涉及 Phone Use 的屏幕截图、0–1000 坐标或手势实现。

DOM 支持同源 iframe 和开放 Shadow DOM。跨源 iframe、封闭 Shadow DOM、画布以及拒绝合成事件的网站，
不能保证用 DOM 操作完成。网页内容按不可信内容传给模型。超时不自动重试点击或提交，应先读取结果，避免重复操作。
JavaScript 执行没有向网页暴露 Android JavascriptInterface。

## 文件与下载

手动上传使用 Android 文件选择器。AI 上传使用聊天绑定工作区的项目文件，复用 WorkspaceRepository 和 FileProvider；
SSH 文件需要先下载到本地后手动选择，不增加远端上传通道。

`rikkaplus://workspace/site/index.html` 可在浏览器打开绑定工作区项目文件中的 HTML，并读取相对路径资源。
内部映射为每个标签独立的虚拟 HTTPS 域名，通过 WebView 请求拦截读取，保留查询参数和片段。
不监听端口，不恢复被删除的 Web 服务；原有 Markdown、HTML 和 SVG 文件预览仍保留。
单个工作区网页资源上限为 16 MiB。

HTTP(S) 下载复用 Android DownloadManager，携带对应网站 Cookie 和 User-Agent，下载至应用的外部文件下载目录。
下载面板展示系统状态，可打开或删除下载。删除操作同时取消任务并删除对应文件。
当前不支持 `blob:` / `data:` 下载、下载暂停恢复及浏览器扩展；网站摄像头和麦克风请求经界面和系统授权处理。

## 与其他工具配合

浏览器、工作区、Termux 和 Shizuku 可注册到同一轮对话，由 AI 按任务需要串联调用；助手须启用对应工具，
工作区就绪，Termux 和 Shizuku 也须分别完成服务配置及授权。

- 工作区编写 HTML/CSS/JavaScript 或处理项目文件，浏览器负责网页预览、操作和项目文件上传。
- Termux 在后台会话中启动本地服务后，浏览器可访问 `http://127.0.0.1:端口`；脚本处理和网页交互由对应工具完成。
- Shizuku 执行其服务权限范围内的系统命令、设备查询或应用启动，浏览器完成网页流程；原有审批规则继续适用。

Termux 与本应用的私有目录和运行环境没有自动共享，传文件需使用可访问的共享目录、导入或本地服务。
SSH 工作区继续使用远端环境，网页上传的远端文件仍需先下载到本地。上述组合尚未进行真机联测。

## 存储与清理

- `filesDir/browser/state.json`：AtomicFile 原子写入标签、选中标签、书签、最近 500 条去重历史、下载 ID 及浏览器偏好。
- 登录数据和网站存储由系统 WebView 管理；下载任务由系统 DownloadManager 管理。
- 助手工具开关沿用现有助手序列化设置；网页截图沿用 FilesManager 聊天附件。
- 不新增数据库、表、迁移或浏览器专用 DataStore。浏览器元数据和网页登录数据目前不包含在应用 ZIP/WebDAV/S3 备份中。
- 「清理网站数据」关闭所有浏览器标签，清理 Cookie、网站存储和缓存；书签及历史保留。

## 来源

1. [Chevey339/kelivo](https://github.com/Chevey339/kelivo/tree/240226975ef1918f8c7cab109ab2a9227dfe7c21)：
   DOM 自动化脚本直接改编自该版本的
   [browser_automation_script.dart](https://github.com/Chevey339/kelivo/blob/240226975ef1918f8c7cab109ab2a9227dfe7c21/lib/core/services/browser/browser_automation_script.dart)，
   在 `app/src/main/assets/browser/automation.js` 保留出处。原脚本的元素引用、输入验证、同源框架和 Shadow DOM 处理沿用，
   修改全局命名及工作区协议，并用本项目 Kotlin 工具封装。标签按聊天归属、共享网站登录和限制原生页面数量也参考其实现。
2. [AAswordman/Operit](https://github.com/AAswordman/Operit/tree/16cffb98f52a11dc77bd0a789ffa0b01ed398ee4)：
   参考原生 WebView 宿主与控制器分离、网页上传及系统权限接入方式，没有复制其整个浏览器、悬浮窗、用户脚本或下载框架。

本仓库继续使用 GNU AGPL v3，见 [LICENSE](../LICENSE)。本功能不修改现有 Phone Use、截图实现、MCP、Termux 或 SSH 工具。

## 验证

- GitHub Debug Kotlin 编译、针对改动文件的格式检查及相关 95 项 JVM 测试通过，零失败、零跳过。
- BrowserStateTest / BrowserToolTest 覆盖标签归属、恢复、历史上限、地址处理、工具参数绑定、截图内容类型、取消及错误返回。
- 回归助手设置、Phone Use、插话队列和输入状态。
- `tools/browser/dom-smoke.mjs` 使用应用中的实际脚本，在 Chromium 中验证 26 项 DOM 行为；可通过
  `RIKKA_PLAYWRIGHT_MODULE` 指定已有 Playwright 模块，通过 `RIKKA_BROWSER_CHANNEL` 选择已安装浏览器（默认 Edge）。

已完成本地 GitHub Debug 通用 APK 构建，核对签名、包名、版本、ARM64/x86_64 架构，以及浏览器脚本和类文件，
确认安装包包含本次浏览器改动。后续 Debug 包在本地构建，Release 包统一通过 GitHub Actions 云端构建，
普通提交和构建不修改版本号，也不自动发布正式版本。

本功能首个正式发布版本为 `3.2.0`，与上游当前公开版本一致；本项目版本代码从 198 提升至 199，
发布标签使用 `1.0-v3.2.0`，发布说明见 [3.2.0](releases/3.2.0.md)。

尚未进行 Android 真机验证。Chromium 脚本测试不代表已验证 Android 页面布局、原生网页截图、系统文件选择器、
下载通知或各网站登录流程；这些需在安装包中进一步实测。
