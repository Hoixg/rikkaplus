<div align="center">
  <img src="docs/icon.png" alt="RikkaPlus icon" width="100" />
  <h1>RikkaPlus · 1.0</h1>
  <p>An Android AI client for multi-model chat, workspace files, and tool use.</p>

English | [简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)
</div>

## Project focus

RikkaPlus is a native Android AI client based on [RikkaHub](https://github.com/rikkahub/rikkahub), maintained by Hoixg. It is designed for individuals who want to use multiple models, manage project files, and work with tools from their phones.

Chat is the main entry point for model settings, assistants, workspace files, MCP services, and the Termux terminal. Connect the model services you use, then work with the AI to organize information, handle workspace files, or run terminal tasks in the context of a conversation.

`1.0` is the project's primary development branch. It focuses on clear configuration, controllable tool operations, and a consistent Material 3 Expressive interface.

## What it is for

- Everyday conversations, writing, and organizing information, with models and assistants chosen for each task.
- Managing project files in a workspace and working with their contents through chat.
- Using MCP tools or running scripts, interactive commands, and background tasks in Termux on the phone.

## Capabilities

| Area | Scope |
| --- | --- |
| Models and keys | Configure multiple model services, manage API keys, select keys manually, and bind keys to models. |
| Assistants and chat | Configure assistants, prompts, and tools, with conversation context and execution records organized by chat. |
| Workspaces | Manage workspace files and directories, and open the relevant workspace from the chat page. |
| Termux | Run commands and scripts, use persistent terminals and background tasks, and inspect status and output. |
| MCP | Configure MCP services in extension management, with switches and approval settings for AI configuration operations. |
| Backup and restore | Save settings, conversations, and files locally or through WebDAV or S3. Workspace backups cover the Files tab. |

## Downloads

- [Releases and APKs](https://github.com/Hoixg/rikkaplus/releases)
- [1.0 development builds](https://github.com/Hoixg/rikkaplus/releases/tag/1.0-nightly)
- [Manual cloud builds](https://github.com/Hoixg/rikkaplus/actions/workflows/release.yml)

## Branches

| Branch | Purpose |
| --- | --- |
| [`1.0`](https://github.com/Hoixg/rikkaplus/tree/1.0) | The project's primary development branch. |
| [`custom`](https://github.com/Hoixg/rikkaplus/tree/custom) | Historical customized branch, available for existing users and feature reference. |

`1.0` is a branch name. Refer to the APK for the application version.

## Development

Built with Kotlin, Jetpack Compose, Material 3 Expressive, Room, and DataStore.

Open the project in Android Studio and prepare the Android SDK and appropriate `app/google-services.json` according to the project's build configuration.

Discuss issues and suggestions in [this repository's Issues](https://github.com/Hoixg/rikkaplus/issues).

## Upstream and license

RikkaPlus is based on [RikkaHub](https://github.com/rikkahub/rikkahub), created by [re-ovo](https://github.com/re-ovo). Thanks to the original author and contributors.

The project retains the [GNU Affero General Public License v3.0](LICENSE) and original copyright notices.
