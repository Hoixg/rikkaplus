# 上下文占用圆环动画

## 显示与交互

- `ChatPage.kt` 的 `ContextUsageRingButton` 保留 40dp 点击区域、25dp 圆环、2.5dp 线宽和原有百分比字号，点击仍显示当前 token 占用及显示容量。
- 暗色渐变：`#E4906E` → `#DF8CAB` → `#AA9BDB`；亮色渐变：`#B56342` → `#B95283` → `#8460BC`。使用应用 `LocalDarkMode`，遵循手动选择的深浅色模式。
- 渐变方向在系统动画倍率为 1 时每 9 秒转一周。弧线始终从顶部开始，流光不改变占用弧长或数值。
- 占用变化以 500ms 过渡，弧线与数字读取同一个动画值。首次进入或切换会话直接显示当前数值，后续连续更新不会重启流光周期。
- 预警仍按原有模型窗口、压缩比例和 token 上限计算；预警渐变为主题错误色及其轻微明暗变化，数字使用主题错误色。占用估算、显示容量、0–100% 钳制和详情文字保持原有逻辑。
- 流光相位只在 Canvas 绘制时读取；百分比单独组合，通过 `derivedStateOf` 在整数变化时更新，避免流光持续重组顶栏。

## 动画开关与生命周期

- **偏好设置 → 主题 → 上下文圆环动画**，默认开启。
- 关闭后显示静态渐变，数值直接更新，同时停止流光和数值过渡。重新开启立即显示当前占用后恢复流光。
- 开关写入 DataStore 的 `context_usage_ring_animation`；设置模型字段为 `enableContextUsageRingAnimation`。正常保存、备份恢复和读取均包含该设置，旧设置缺失时默认开启，无需数据库迁移。
- 非零占用且当前页面生命周期为 `RESUMED` 时创建无限动画；零占用、页面离开前台和组件移除时移除该动画。
- 页面离开前台时也停止数值过渡；后台收到的新占用直接更新，返回前台以当前值开始显示。
- Compose 原生动画遵循系统动画倍率。倍率为零时有限动画直接完成，无限动画显示静态渐变并挂起等待倍率恢复，不使用轮询或自定义计时器。
- 页面返回前台时从当前占用恢复流光；不保存跨页面的流光相位。

实现依据：[Compose InfiniteTransition 源码](https://github.com/androidx/androidx/blob/androidx-main/compose/animation/animation-core/src/commonMain/kotlin/androidx/compose/animation/core/InfiniteTransition.kt) 的零倍率挂起处理，以及 [Navigation 3 NavDisplay 源码](https://github.com/androidx/androidx/blob/androidx-main/navigation3/navigation3-ui/src/commonMain/kotlin/androidx/navigation3/ui/NavDisplay.kt) 的页面生命周期管理。依赖行为仍需实机验证。

## 验证记录（2026-10-04）

命令：

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest `
  --tests 'me.rerere.rikkahub.utils.ContextWindowTest' `
  --tests 'me.rerere.rikkahub.data.datastore.SettingsContextRingAnimationTest' `
  :app:compileDebugAndroidTestKotlin --console=plain --max-workers=1 `
  '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -XX:ActiveProcessorCount=2'
```

最终 `BUILD SUCCESSFUL`，29 项单元测试均通过，应用 Kotlin 与设备测试代码编译通过。首次加入开关后完整验证耗时 1 分 10 秒；补齐后台数值过渡停止逻辑后最终增量验证耗时 18 秒。日志：`app/build/reports/context-ring-animation-check-20261004.log`。

| 检查 | 范围与状态 |
| --- | --- |
| 原有上下文单元测试 | 27 项通过；覆盖容量、钳制、压缩阈值等 |
| 设置兼容性 | 新增 2 项通过：旧 JSON 默认开启、关闭状态与其他主题设置的备份往返 |
| 设备测试代码 | 新增 7 项已编译：初始值与流光、占用升降、零值与生命周期、系统零倍率、开关、主题预警及超容量、会话切换及大字体点击详情；未执行 |
| 实机交互 | `adb devices` 无可用设备，设备测试未执行。后台暂停恢复、系统关闭动画、320dp/大字体、实际顶栏点击和设置页开关持久化尚未实机验证 |
| 静态检查 | 圆环尺寸、线宽、起点、阈值、容量与详情逻辑保留；流光读取限制在绘制阶段；正常保存与备份恢复入口均持久化开关 |

`git diff --check` 通过，新文件也已做空白检查。构建中存在项目已有的 Navigation 3 opt-in 等警告；新增设备测试使用的旧版 `createComposeRule` API 有弃用警告，不影响本次编译。

保留已有图片生成工具改动及未跟踪内容。本轮不提交 GitHub、不生成安装包、不发布。
