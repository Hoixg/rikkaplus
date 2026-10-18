<div align="center">
  <img src="docs/icon.png" alt="RikkaPlus 图标" width="100" />
  <h1>RikkaPlus · 1.0</h1>
  <p>基于最新 RikkaHub 原版，重新构建 RikkaPlus。</p>

[English](README.md) | 简体中文 | [繁體中文](README_ZH_TW.md)
</div>

## 分支介绍

`1.0` 是 [RikkaPlus](https://github.com/Hoixg/rikkaplus) 的新重构分支，由 Hoixg 维护。

本分支从上游 [RikkaHub](https://github.com/rikkahub/rikkahub) 原版出发，重新梳理和实现 `custom` 中积累的定制功能，逐步解决早期开发留下的技术债务、功能耦合和数据结构负担，让后续开发与同步上游更容易维护。

## 分支分工

| 分支 | 定位 |
| --- | --- |
| [`1.0`](https://github.com/Hoixg/rikkaplus/tree/1.0) | 新重构开发分支，以原版为基础重新组织和实现定制能力。 |
| [`custom`](https://github.com/Hoixg/rikkaplus/tree/custom) | 现有定制版，保留用于日常使用与功能对照。 |

`1.0` 是分支名称，当前上游应用基线为 **RikkaHub 3.0.0**。

## 重构目标

- 逐项整理定制功能，明确需要保留、简化和重新设计的内容。
- 理清功能逻辑、聊天生成、界面与数据存储之间的职责。
- 根据实际使用情况梳理废弃表、冗余字段和历史兼容逻辑。
- 统一 Material 3 Expressive 界面与交互。
- 每项功能逐步实现和验证，让改动便于审查，也便于持续同步上游。

定时任务、子代理、生图审批和工具交互等其余定制能力将纳入评估，具体保留范围与实现进度以后续开发记录为准。

## 当前状态

**重构处于起步阶段。** 当前以上游原版为基础，已完成多 Key 管理，其余定制功能逐项重建。

旧数据兼容、数据库迁移、导入和覆盖升级方案暂未确定，后续单独讨论。

现有定制版安装包见 [RikkaPlus Releases](https://github.com/Hoixg/rikkaplus/releases)，请根据对应发布说明确认版本所属分支。

### 已完成的整理

- 移除 Web 服务器、浏览器聊天界面、相关后台服务与前端构建依赖。
- 重建多 Key 管理：新增、编辑、删除、名称与倍率备注、手动切换、按模型指定密钥，使用 Material 3 Expressive 分段界面。
- 移除内置中转站预设与推广推荐入口，保留官方供应商和常用云平台。

## 自动构建与云端发布

仓库默认开发分支为 `1.0`。

- **每日自动构建**：北京时间每天 02:00、17:00 检查更新，无新提交时跳过。签名安装包发布至独立的 [1.0-nightly](https://github.com/Hoixg/rikkaplus/releases/tag/1.0-nightly)。
- **手动云构建**：进入 [Actions](https://github.com/Hoixg/rikkaplus/actions/workflows/release.yml)，选择 `1.0` 分支和 `release`／`debug` 类型，完成后下载构建产物。手动构建不发布新版本。
- **版本发布**：推送与应用版本匹配的附注标签，例如 `1.0-v3.0.1`，触发签名构建与发布。与旧 custom 版本的标签分开管理。
- **更新说明**：正式发布优先读取 `docs/releases/<应用版本号>.md`，参考[说明模板](docs/releases/TEMPLATE.md)；其他构建按分类整理提交标题和正文。

云构建复用仓库密钥 `KEY_BASE64`、`SIGNING_CONFIG` 和 `GOOGLE_SERVICES_JSON`，也可为本分支单独配置优先使用的 `GOOGLE_SERVICES_JSON_1_0`。复用已有 Firebase 项目配置时，构建会匹配当前包名；使用 Firebase 服务时，应通过分支专属密钥提供已注册的 Android 客户端配置。

## 开发

主要技术为 Kotlin、Jetpack Compose、Material 3 Expressive、Room 和 DataStore。

使用 Android Studio 打开项目，按上游构建配置准备环境。Android 构建需要相应的 `app/google-services.json` 与本地 SDK 配置。

本分支的功能规划和问题请在[本仓库](https://github.com/Hoixg/rikkaplus/issues)讨论。

## 上游与许可证

RikkaPlus 基于 [re-ovo](https://github.com/re-ovo) 创建的 [RikkaHub](https://github.com/rikkahub/rikkahub)。感谢原作者及贡献者。

项目保留 [GNU Affero General Public License v3.0](LICENSE) 和原有版权声明。
