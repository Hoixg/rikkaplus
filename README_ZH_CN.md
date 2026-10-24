<div align="center">
  <img src="docs/icon.png" alt="RikkaPlus 图标" width="100" />
  <h1>RikkaPlus · 1.0</h1>
  <p>RikkaPlus 的主要开发与维护分支。</p>

[English](README.md) | 简体中文 | [繁體中文](README_ZH_TW.md)
</div>

## 分支定位

`1.0` 是 RikkaPlus 的主要开发分支，基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 上游，围绕当前架构重新整理项目的定制功能，作为后续持续开发的基础。

这一分支以清晰的代码结构、可维护的功能实现和一致的交互体验为主要方向。定制功能按本分支的需求重新设计，并在维护过程中清理废弃代码、冗余依赖和不必要的数据逻辑。

## 维护方向

- 持续跟进上游开发，根据本分支定位选择和整合变更。
- 优先复用现有架构、组件和工具，控制重复实现与维护成本。
- 围绕本分支设计定制功能及其数据结构，独立维护包名、签名和发布流程。

## 分支关系

| 分支 | 定位 |
| --- | --- |
| [`1.0`](https://github.com/Hoixg/rikkaplus/tree/1.0) | 当前主要开发与维护分支，功能开发、上游整合和版本发布在此进行。 |
| [`custom`](https://github.com/Hoixg/rikkaplus/tree/custom) | 历史定制版存档，保留在 GitHub，停止维护。 |

`1.0` 是分支名称，应用版本以发布记录标注为准。

## 获取与反馈

[发布版本](https://github.com/Hoixg/rikkaplus/releases) · [每日构建](https://github.com/Hoixg/rikkaplus/releases/tag/1.0-nightly) · [问题反馈](https://github.com/Hoixg/rikkaplus/issues)

## 致谢与许可证

项目基于 [re-ovo](https://github.com/re-ovo) 及贡献者开发的 [RikkaHub](https://github.com/rikkahub/rikkahub)。组件来源说明见 [Shizuku 文档](docs/shizuku.md)与[终端模块说明](terminal/README.md)。

项目采用 [GNU Affero General Public License v3.0](LICENSE)，保留上游版权声明。
