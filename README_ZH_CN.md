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

多 Key、定时任务、子代理、生图审批和工具交互等定制能力将纳入评估，具体保留范围与实现进度以后续开发记录为准。

## 当前状态

**重构处于起步阶段。** 当前代码基于上游原版，定制功能尚未在本分支完成重建。

旧数据兼容、数据库迁移、导入和覆盖升级方案暂未确定，后续单独讨论。

现有定制版安装包见 [RikkaPlus Releases](https://github.com/Hoixg/rikkaplus/releases)，请根据对应发布说明确认版本所属分支。

## 开发

主要技术为 Kotlin、Jetpack Compose、Material 3 Expressive、Room 和 DataStore。

使用 Android Studio 打开项目，按上游构建配置准备环境。构建 Web 模块还需要 Node.js 和 pnpm；Android 构建需要相应的 `app/google-services.json` 与本地 SDK 配置。

本分支的功能规划和问题请在[本仓库](https://github.com/Hoixg/rikkaplus/issues)讨论。

## 上游与许可证

RikkaPlus 基于 [re-ovo](https://github.com/re-ovo) 创建的 [RikkaHub](https://github.com/rikkahub/rikkahub)。感谢原作者及贡献者。

项目保留 [GNU Affero General Public License v3.0](LICENSE) 和原有版权声明。
