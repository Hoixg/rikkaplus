# 应用图标

当前图标使用用户于 2026-10-26 提供的原图，原文件保存在 `tools/branding/launcher-source.jpg`。
只按 Android 资源规格缩放和排版，不重绘图片。

- `mipmap-mdpi` 至 `mipmap-xxxhdpi`：旧版桌面图标分别为 48、72、96、144、192 px。
- 自适应图标图层为 108 dp，原图占中间的 72 dp，外侧保留 18 dp 透明边距；黑色背景填充系统蒙版的边缘。
  应用内的 `AppLogo` 沿用同一套图层，聊天图片导出的图标也使用这些资源。
- `docs/icon.png` 为 512×512，供 README 展示。
- 不再声明或保留旧图标的 monochrome 图层，防止主题图标继续显示旧品牌图形。

需要重新导出时，准备 Node.js 和 `sharp`，运行 `node tools/branding/generate-icons.cjs`。
`sharp` 只用于维护图标，正常 Android 编译不需要 Node.js 或这个依赖。

同步上游时保留这套图标及原图，不用上游图标覆盖。
