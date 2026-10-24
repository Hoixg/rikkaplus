<div align="center">
  <img src="docs/icon.png" alt="RikkaPlus icon" width="100" />
  <h1>RikkaPlus · 1.0</h1>
  <p>The primary development and maintenance branch of RikkaPlus.</p>

English | [简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)
</div>

## Branch purpose

`1.0` is the main development branch of RikkaPlus. Based on [RikkaHub](https://github.com/rikkahub/rikkahub), it reorganizes the project's custom functionality around the current upstream architecture and provides the foundation for ongoing development.

The branch focuses on clear code structure, maintainable implementations, and consistent interactions. Custom features are designed for this branch, while obsolete code, redundant dependencies, and unnecessary data handling are removed as the project is maintained.

## Maintenance direction

- Follow upstream development and integrate changes that fit this branch's scope.
- Reuse existing architecture, components, and tools to keep duplicate implementations and maintenance costs under control.
- Design custom features and their data structures for this branch, with its own application ID, signing, and release process.

## Branches

| Branch | Role |
| --- | --- |
| [`1.0`](https://github.com/Hoixg/rikkaplus/tree/1.0) | Active development and maintenance. Feature work, upstream integration, and releases take place here. |
| [`custom`](https://github.com/Hoixg/rikkaplus/tree/custom) | Historical archive retained on GitHub. No longer maintained. |

`1.0` is a branch name. Application versions are listed in the releases.

## Releases and feedback

[Releases](https://github.com/Hoixg/rikkaplus/releases) · [Daily build](https://github.com/Hoixg/rikkaplus/releases/tag/1.0-nightly) · [Issues](https://github.com/Hoixg/rikkaplus/issues)

## Credits and license

Based on [RikkaHub](https://github.com/rikkahub/rikkahub), created by [re-ovo](https://github.com/re-ovo) and its contributors. Component attribution is documented in the [Shizuku notes](docs/shizuku.md) and [terminal module notes](terminal/README.md).

Licensed under [GNU Affero General Public License v3.0](LICENSE), with upstream copyright notices retained.
