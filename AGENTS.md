# Repository Guidelines

## Project Overview

RikkaPlus is a native Android LLM chat client based on RikkaHub that supports switching between different AI providers
for conversations.
Built with Jetpack Compose, Kotlin, and follows Material 3 Expressive (MD3 Expressive) design language.

## Design Language

The UI follows Material 3 Expressive. New and reworked screens should match it rather than the older baseline
Material 3 look (cards with dividers, plain forms).

- The app theme is `MaterialExpressiveTheme` (app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt); prefer the
  expressive variants of components, shapes (`MaterialShapes`), emphasized typography (e.g. `titleSmallEmphasized`)
  and motion from `androidx.compose.material3` over hand-rolled equivalents.
- Settings-style and form pages use segmented groups via `CardGroup` (
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/CardGroup.kt) instead of a card with dividers.
- Favor larger corner radii, filled/tonal containers, and whole-row click targets.

## Build, Test, and Development Commands

```bash
./gradlew assembleGithubDebug    # 构建 GitHub 渠道的 Debug APK
./gradlew assembleGithubRelease  # 构建 GitHub Release / 每日构建用的 APK
./gradlew buildAll               # 仅构建一个 GitHub Release 通用 APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew lint                   # 运行 Android Lint
./gradlew spotlessApply          # 用 ktlint 格式化相对 origin/master 改动过的 Kotlin 文件
./gradlew spotlessCheck          # 只检查格式不改文件
```

## Branch, commits and releases

The active development branch is `1.0`. Keep the historical `custom` branch on GitHub; do not recreate a local
`custom` checkout unless the user requests it. Preserve the removal of the Web server when syncing upstream.

The `1.0` application ID is `io.github.hoixg.rikkaplus` (`.debug` for Debug). Build only the universal APK
containing ARM64 and x86_64. Each build request produces exactly one APK: Release by default, or Debug only when
explicitly requested. Never build extra variants, per-ABI APKs, or Play AABs alongside it. `buildAll` also builds
only the GitHub Release universal APK. Cloud uploads must use the exact verified APK path, and release pages
must contain exactly one APK. Daily builds remove obsolete APK attachments from `1.0-nightly`; keep historical
versioned releases unchanged. Release signing uses ignored `signing-1.0.properties`; cloud builds use dedicated
`KEY_BASE64_1_0` / `SIGNING_CONFIG_1_0` secrets. Never fall back to the historical custom signing configuration.

Use Chinese commit subjects in the form `type(scope): 改动摘要`. Explain the changes and their reason in the body
when needed. After pushing, report the changes, verification result and commit hash in Chinese. Do not rewrite
published commits merely to improve their wording.

Build workflows:

- `daily-build.yml`: daily signed development builds published as `1.0-nightly`; skip if the source has not changed.
- `release.yml`: manual Debug/Release cloud builds uploaded as Actions artifacts. Tags `1.0-v<versionName>` publish
  an explicitly versioned release. Keep these tags separate from the historical custom releases.
- Use `docs/releases/<versionName>.md` for curated Chinese release notes. Describe user-visible changes by category;
  avoid generic merge titles. The workflow falls back to categorized commit subjects and bodies.

When the user says only `提交`, prepare the next rebuild release: review local changes, sync upstream while preserving
the rebuild's changes, increment the final versionName component and versionCode, verify the necessary code, commit
and push `1.0`, then create an annotated `1.0-v<versionName>` tag. Verify the tag is unused first. Wait for the workflow
and APK assets before reporting a successful release. Ordinary push requests do not publish a version tag.

For local verification, leave CPU headroom: use roughly half the available logical cores, below-normal priority where
supported, and a small Gradle worker limit. Prefer focused checks and avoid repeated full builds. Never commit signing
keys, passwords, Google Services configuration, or generated build metadata.

App 有一个 `distribution` 维度的 product flavor：`github`（默认）和 `play`，共用同一个 applicationId。
渠道差异用 `BuildConfig.IS_PLAY_STORE` 判断（如 Play 版不检查更新、不显示更新提醒）。

## Upstream merge boundaries

Read `docs/upstream-sync.md` before syncing upstream. The built-in Web server is intentionally removed from `1.0`.
Never restore `web/`, `web-ui/`, the app's `web` source/test packages, server settings/routes/startup hooks, or direct
Web-server-only JWT / JmDNS dependencies. Keep the OAuth loopback callback receiver and its Ktor Core/CIO dependencies
required for subscription sign-in. Resolve upstream modify/delete conflicts by keeping these deletions, and
discard newly added server files even when Git reports no conflict. Check newly added locale resources for orphaned
server strings. Keep WebView/HTML/Markdown previews, web search, WebDAV, MCP, SSH and their client networking support.
Run the documented removal checks before committing an upstream merge.

## Scheduled tasks

The `1.0` scheduled-task feature is an independent implementation in `data/automation` and
`ui/pages/extensions/automation`; read `docs/scheduled-tasks.md` before changing it. The `automation_task` and
`automation_run` tables contain only this branch's data. Do not import historical custom task/job schemas,
Worker shims, migration chains, timed approvals, or old-prompt comparison metadata. Reuse ChatService,
ConversationSession, existing model/assistant pickers, and the normal agent tool approvals. Scheduled executions
must keep task management read-only, and model overrides must not change the assistant or conversation default.

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **material3**: Material color utility extensions used by the app UI
- **ui**: Reusable Compose UI components that do not depend on app logic
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **workspace**: Per-workspace project files with a shared local Linux environment by default and an independent environment option at creation; SSH workspaces use their remote environment.
- **terminal**: Terminal emulator core taken from termux-app's `terminal-emulator` (package `com.termux.terminal`, kept
  binary compatible with the `terminal-view` aar). `TerminalSession` runs over a `TerminalTransport`: local PRoot
  terminals use `LocalPtyTransport`, SSH workspaces use the app's `SshTerminalTransport`. Origin and local changes are
  listed in terminal/README.md.

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings.
  Once a conversation is persisted it also holds a `ConversationConfig` snapshot (chat model, reasoning level, search,
  MCP servers, workspace, skills) taken from the assistant; from then on chat-page changes to those settings stay on
  the conversation, and code should read them through `Settings.getAssistantOf(conversation)` /
  `Settings.getChatModelOf(conversation)` instead of the assistant directly. Once loaded, the in-memory
  `ConversationSession` is the single source of truth: change a conversation through `ChatService` (which applies
  `session.update { latest -> ... }` and then `flush()`), never by saving a whole `Conversation` snapshot or one read
  back from the database; deleting also goes through `ChatService` so a running generation cannot re-insert it. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt,
  app/src/main/java/me/rerere/rikkahub/data/model/ConversationConfig.kt,
  app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- Add, translate or clean up strings with the `tools/i18n` CLI (Bun); see the `i18n-localization` skill.
