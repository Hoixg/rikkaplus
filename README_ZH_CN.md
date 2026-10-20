<div align="center">
  <img src="docs/icon.png" alt="RikkaPlus 图标" width="100" />
  <h1>RikkaPlus · 1.0</h1>
  <p>面向多模型聊天、工作区文件与工具协作的 Android AI 客户端。</p>

[English](README.md) | 简体中文 | [繁體中文](README_ZH_TW.md)
</div>

## 项目定位

RikkaPlus 是基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的 Android 原生 AI 客户端，由 Hoixg 维护，面向希望在手机上使用多种模型、管理项目文件并调用工具完成任务的个人用户。

项目以聊天为主要入口，将模型配置、助手、workspace 文件、MCP 和 Termux 终端放在同一个应用中。你可以连接自己使用的模型服务，让 AI 结合对话内容处理资料、操作工作区文件或执行终端任务。

`1.0` 是项目的主要开发分支，注重配置清晰、工具操作可控，以及一致的 Material 3 Expressive 界面体验。

## 主要用途

- 日常聊天、内容创作与资料整理，按任务选择模型和助手。
- 围绕 workspace 管理项目文件，通过聊天查看和处理文件内容。
- 通过 MCP 使用扩展工具，或借助手机上的 Termux 执行脚本、交互命令和后台任务。

## 能力概览

| 方向 | 内容 |
| --- | --- |
| 模型与密钥 | 配置多个模型服务，管理多把 API Key，手动选择密钥及按模型绑定。 |
| 助手与对话 | 配置助手、提示词和工具，按聊天组织上下文与执行记录。 |
| 工作区 | 管理 workspace 的文件与目录，从聊天页进入对应工作区。 |
| Termux | 执行命令和脚本，使用持久终端及后台任务，查看状态与输出。 |
| MCP | 在扩展管理中配置 MCP 服务，并通过开关和审批设置控制 AI 配置操作。 |
| 备份与恢复 | 使用本地、WebDAV 或 S3 保存配置、聊天记录及文件；工作区备份覆盖“文件”页的内容。 |

## 获取安装包

- [版本与安装包](https://github.com/Hoixg/rikkaplus/releases)
- [1.0 开发构建](https://github.com/Hoixg/rikkaplus/releases/tag/1.0-nightly)
- [手动云构建](https://github.com/Hoixg/rikkaplus/actions/workflows/release.yml)

## 分支

| 分支 | 用途 |
| --- | --- |
| [`1.0`](https://github.com/Hoixg/rikkaplus/tree/1.0) | 项目的主要开发分支。 |
| [`custom`](https://github.com/Hoixg/rikkaplus/tree/custom) | 历史定制分支，供既有用户使用与功能参考。 |

`1.0` 是分支名称，应用版本以安装包标注为准。

## 开发

主要技术为 Kotlin、Jetpack Compose、Material 3 Expressive、Room 和 DataStore。

使用 Android Studio 打开项目，按项目构建配置准备 Android SDK 和相应的 `app/google-services.json`。

问题与建议请在[本仓库 Issues](https://github.com/Hoixg/rikkaplus/issues)讨论。

## 上游与许可证

RikkaPlus 基于 [re-ovo](https://github.com/re-ovo) 创建的 [RikkaHub](https://github.com/rikkahub/rikkahub)。感谢原作者及贡献者。

项目保留 [GNU Affero General Public License v3.0](LICENSE) 和原有版权声明。
