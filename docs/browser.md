# 内置浏览器

## 使用入口

- 聊天页点击加号，再点「浏览器」，进入独立全屏页。
- 主页中央仅保留圆角填充搜索框，点击搜索按钮或键盘搜索即可搜索网页/打开网址；不显示收藏、历史、介绍或空记录提示。
- 支持网址或搜索、前进后退、刷新或停止加载、多个标签、书签、历史、页内查找、上传和下载。
- 顶部使用紧凑地址栏，平时显示站点域名，点击后显示并全选完整网址；支持清空、键盘前往和返回退出编辑。刷新/停止位于右侧，加载进度叠加在底部，不挤动网页。
- 底部依次为后退/前进组合、新建标签、带数量的标签入口和菜单。菜单统一提供收藏此页、书签、历史、下载、设置、页内查找和外部浏览器入口。
- 标签面板采用自适应卡片网格，展示标题和站点，当前标签突出显示；当前聊天与其他聊天分组，原有打开和关闭行为保持。标签卡片不额外截取网页预览。
- 设置中可选择搜索引擎、桌面网站、自定义 User-Agent、恢复标签及记录历史；管理面板统一标题、关闭按钮和卡片层次，保存按钮固定在底部。
- 书签与历史只在各自的面板中显示，筛选框标明「搜索书签」或「搜索历史记录」；空列表不显示筛选框。下载显示状态、已下载大小与进度。
- 浏览器设置按搜索、浏览、高级和网站数据分组，搜索引擎用下拉列表选择，保存按钮固定在面板底部。「清理网站数据」统一放在设置中，沿用原来的确认流程。
- 在助手的「本地工具」中打开「浏览器」，AI 才会获得 `browser` 工具。手动浏览不依赖这个开关。
- 聊天中的浏览器工具结果可直接打开相同标签。AI 操作期间可以点击「接管」暂停后续操作，点击「继续」恢复。
- AI 状态以网页右上角的小胶囊显示当前动作，保留接管/继续，不挤占网页高度。点击、填写和选择显示圈点，实际滚动显示滑动轨迹；动画由独立 Canvas 绘制，不再通过重绘网页内容显示。
- 聊天只在打开、显示或切换网页时展示整张可点击的网页标题卡片，简写站点域名；后续点击、输入、读取、滚动等步骤不再重复显示长链接与「打开浏览器」按钮。

自定义 User-Agent 用于替换浏览器向网站发送的设备和浏览器身份信息，通常留空即可；查看电脑版网页可直接打开「桌面网站」。

页面加载进度使用独立状态，只更新地址栏进度条；网址、标题、历史和网页状态由各自原生事件更新，进度回调不再读取多项 WebView 属性或触发元数据保存。
主页仍仅保留搜索功能。主页面加载失败显示独立错误页和重新加载按钮，错误提示不会反复挤动网页视口。

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
点击、输入及目标定位直接沿用 Ke 当前 DOM 实现：`button=left/right`、`click_count=1/2`，输入默认替换，`clear_first=false` 追加并验证实际值。
导航默认等待 DOMContentLoaded，不等图片等资源全部加载，移除固定 120ms 延迟及加载状态轮询。
脚本按 Ke 的 `ok/value/error` 格式通过原生回调交付 Promise 的最终结果，不再每 60ms 查询结果槽。两项逻辑都在现有 BrowserEngine 中，
每个引擎只保存一个待返回结果；不增加等待阶段参数、独立执行层或多请求容器。`wait` 保持原有行为。
截图按最长边 1568 像素缩放，并同时返回 CSS 视口尺寸；使用截图坐标时必须按返回比例换算。
这套坐标仅用于网页 DOM，不涉及 Phone Use 的屏幕截图、0–1000 坐标或手势实现。

DOM 支持同源 iframe 和开放 Shadow DOM。跨源 iframe、封闭 Shadow DOM、画布以及拒绝合成事件的网站，
不能保证用 DOM 操作完成。网页内容按不可信内容传给模型。超时不自动重试点击或提交，应先读取结果，避免重复操作。
网页仅能向专用的 JavascriptInterface 交付脚本结果及操作提示的坐标，不提供设备、文件或无障碍操作。
提示只接受当前 AI 正在操作的标签，按实际 CSS 视口映射到浏览器本地绘制层；不会接收触摸，也不会进入 WebView 截图。
动画只在可见浏览器页面播放，不保存到元数据；提示失败不会阻止、重试或改变网页操作。

## 网页兼容性

继续使用系统 WebView，不额外下载独立内核。默认保留真实 User-Agent，仅在用户更改桌面模式或自定义标识时调整；保存其他设置不重设 UA 或重载网页。
手机和桌面模式都启用标准 viewport 与 overview，JavaScript、DOM storage、Cookie 和第三方 Cookie 保持可用。
验证所需的 HTTPS、`about:blank`、`about:srcdoc` 及内嵌文档交由 WebView 原生处理；普通网络请求不再进入工作区资源查询，工作区虚拟域名仍复用原有文件通道。
加载完成不再在主线程同步调用 `CookieManager.flush()`；登录数据沿用 WebView 管理，离开网页时在 IO 线程保存 Cookie，清除网站数据也不在主线程执行该同步操作。
原生页面提交根据当前实际网址确认，可接受服务端重定向；新加载开始前的旧页面完成回调、停止和失败均不会使本次等待错误地成功。

截图中的 `600010` 属于 Cloudflare 官方 `600*` 通用验证失败，不能仅凭第三方检测页文案确定具体触发因素。
上述修改处理应用的兼容性和主线程阻塞，不伪造指纹或代替用户完成验证码；同日用户已反馈当前设备和网络下 CF 验证通过。
参考 [Cloudflare 错误码](https://developers.cloudflare.com/turnstile/troubleshooting/client-side-errors/error-codes/)及 [WebView 接入要求](https://developers.cloudflare.com/turnstile/get-started/mobile-implementation/)。

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

1. [Chevey339/kelivo](https://github.com/Chevey339/kelivo/tree/4d69846f6c7efa64f374ad6bc6a842f1dec92a56)：
   DOM 自动化脚本直接沿用该版本的
   [browser_automation_script.dart](https://github.com/Chevey339/kelivo/blob/4d69846f6c7efa64f374ad6bc6a842f1dec92a56/lib/core/services/browser/browser_automation_script.dart)，
   在 `app/src/main/assets/browser/automation.js` 保留出处。原脚本的元素引用、输入验证、同源框架和 Shadow DOM 处理沿用，
   仅修改全局命名、工作区协议并添加既有手势显示钩子，用本项目 Kotlin 工具绑定；已逐行核对 DOM 核心一致。
   异步回传沿用其 `evaluate` 的结果格式，原生 WebView 接口通过现有桥接适配。标签按聊天归属、共享网站登录和限制原生页面数量也参考其实现。
2. [AAswordman/Operit](https://github.com/AAswordman/Operit/tree/16cffb98f52a11dc77bd0a789ffa0b01ed398ee4)：
   参考原生 WebView 宿主与控制器分离、网页上传及系统权限接入方式，没有复制其整个浏览器、悬浮窗、用户脚本或下载框架。
3. [Kelivo 的执行与加载等待](https://github.com/Chevey339/kelivo/blob/4d69846f6c7efa64f374ad6bc6a842f1dec92a56/lib/core/services/browser/browser_native_engine.dart#L1774)：
   2026-11-13 按其默认 DOMContentLoaded、事件等待及 `callAsyncJavaScript` 完成回传方式调整执行链路。
   Android WebView 通过现有页面桥接接收完成回调；复用现有引擎、会话绑定和接管行为，不引入新的框架、数据库、审批开关或依赖。

4. 2026-11-14 对照 Ke `6ebbfef53d1f0e845524e1c686cb12bcca698e2a` 的
   [原生进度与标题回调](https://github.com/Chevey339/kelivo/blob/6ebbfef53d1f0e845524e1c686cb12bcca698e2a/lib/core/services/browser/browser_native_engine.dart#L1185)、
   [元数据保存](https://github.com/Chevey339/kelivo/blob/6ebbfef53d1f0e845524e1c686cb12bcca698e2a/lib/core/services/browser/browser_service.dart#L1527)和
   [紧凑浏览控件](https://github.com/Chevey339/kelivo/blob/6ebbfef53d1f0e845524e1c686cb12bcca698e2a/lib/features/browser/widgets/browser_controls.dart)重整宿主事件与页面。
   继续沿用前述 DOM 核心及异步完成协议；Flutter 专用玻璃、纹理和平台合成代码不适用于本项目原生 Compose 宿主。

本仓库继续使用 GNU AGPL v3，见 [LICENSE](../LICENSE)。本功能不修改现有 Phone Use、截图实现、MCP、Termux 或 SSH 工具。

## 验证

2026-11-14 按 Ke 重整加载事件、进度、Cookie 保存、页面和手势绘制。GitHub/Play Debug Kotlin 编译通过，27 项相关 JVM 测试及 49 项 Chromium 脚本检查通过。
新增 Android 原生回归用例覆盖默认 UA、标准 viewport、临时 HTML 和 srcdoc，测试源码编译通过；本地没有可用 Android 设备，原生用例尚未执行，滚动耗时尚未测量。同日用户反馈此 Debug 包的 CF 验证通过，记录为用户设备上的验证结果。
未修改数据库、版本、依赖或构建发布流程。本地 GitHub Debug 通用包已构建，签名、版本、ARM64/x86_64 架构及新浏览器组件和脚本打包校验通过。

2026-11-13 浏览器布局重排：顶部地址与刷新合并、底部操作分组、标签卡片网格和管理面板样式统一。
GitHub / Play Debug Kotlin 编译及定向格式检查通过；复用现有 WebView 宿主与手势绘制，没有增加截图任务或执行框架。
随后完成本地 GitHub Debug 通用 APK 构建，核对包名、3.2.0 / 199、原有 Debug 签名、ARM64 / x86_64、实际脚本与布局类通过，确认已删除的执行层未进入安装包。
Android 真机的布局、字号和键盘显示效果尚需验证。

2026-11-13 执行链路精简为默认 DOM 等待和单次结果回调，复用 BrowserEngine。
删除额外执行类、多请求容器、四阶段参数和条件等待扩展；保留会话归属、用户接管及现有内存上限。
对齐 Ke `4d69846f` 后，已逐行核对 DOM 核心；GitHub / Play Debug Kotlin 编译、定向格式检查、19 项相关 JVM 测试及 49 项 Chromium 检查通过。
新增检查覆盖 Ke 的右键、双击、追加输入及默认替换输入，既有手势显示保持可用。
上述 Debug 构建已包含执行链路调整，未进行 Android 真机耗时对比。

2026-11-12 主页与面板布局调整：定向格式检查及 GitHub Debug Kotlin 编译通过。
本地干净源码不含 Google 服务私有配置，编译检查使用 `-x :app:processGithubDebugGoogleServices` 跳过配置生成；未构建安装包。
另核对顶部地址栏及底部工具栏与修改前的布局一致，主页不再调用书签列表组件。本次未改浏览器引擎或工具执行逻辑。

同日操作反馈调整：GitHub / Play Debug Kotlin 编译、定向格式检查及 40 项相关 JVM 测试通过，35 项 Chromium DOM 检查通过。
新增检查覆盖 CSS 视口坐标、重复点击动画事件、非法提示数据忽略、真实滚动方向/边界、提示失败不改变操作，以及聊天入口不重复显示。
编译时跳过两个渠道缺失的 Google 服务私有配置生成；本次没有构建安装包，Android 真机的动画和显示效果仍需实测。

随后恢复原有的本地 Google 服务配置，完成包含上述改动的 GitHub Debug 通用 APK 构建；核对包名、3.2.0 / 199、
原有 Debug 签名、ARM64 / x86_64 架构和 APK 中的浏览器脚本及类文件均通过。安装包仅保留最新一份，Release 包通过云端工作流构建。

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
