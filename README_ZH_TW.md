<div align="center">
  <img src="docs/icon.png" alt="RikkaPlus 圖示" width="100" />
  <h1>RikkaPlus · 1.0</h1>
  <p>面向多模型聊天、工作區檔案與工具協作的 Android AI 用戶端。</p>

[English](README.md) | [简体中文](README_ZH_CN.md) | 繁體中文
</div>

## 專案定位

RikkaPlus 是基於 [RikkaHub](https://github.com/rikkahub/rikkahub) 的 Android 原生 AI 用戶端，由 Hoixg 維護，面向希望在手機上使用多種模型、管理專案檔案並呼叫工具完成任務的個人使用者。

專案以聊天為主要入口，將模型設定、助手、workspace 檔案、MCP 與 Termux 終端整合在同一個應用程式中。你可以連接自己使用的模型服務，讓 AI 結合對話內容處理資料、操作工作區檔案或執行終端任務。

`1.0` 是專案的主要開發分支，注重設定清楚、工具操作可控，以及一致的 Material 3 Expressive 介面體驗。

## 主要用途

- 日常聊天、內容創作與資料整理，依任務選擇模型和助手。
- 圍繞 workspace 管理專案檔案，透過聊天查看和處理檔案內容。
- 透過 MCP 使用擴充工具，或藉助手機上的 Termux 執行腳本、互動命令與背景任務。

## 能力概覽

| 方向 | 內容 |
| --- | --- |
| 模型與金鑰 | 設定多個模型服務，管理多把 API Key，手動選擇金鑰及依模型綁定。 |
| 助手與對話 | 設定助手、提示詞與工具，依聊天組織上下文和執行紀錄。 |
| 工作區 | 管理 workspace 的檔案與目錄，從聊天頁進入對應工作區。 |
| Termux | 執行命令與腳本，使用持久終端和背景任務，查看狀態與輸出。 |
| MCP | 在擴充管理中設定 MCP 服務，並透過開關和審批設定控制 AI 設定操作。 |
| 備份與還原 | 使用本機、WebDAV 或 S3 儲存設定、聊天紀錄與檔案；工作區備份涵蓋「檔案」頁的內容。 |

## 取得安裝包

- [版本與安裝包](https://github.com/Hoixg/rikkaplus/releases)
- [1.0 開發建構](https://github.com/Hoixg/rikkaplus/releases/tag/1.0-nightly)
- [手動雲端建構](https://github.com/Hoixg/rikkaplus/actions/workflows/release.yml)

## 分支

| 分支 | 用途 |
| --- | --- |
| [`1.0`](https://github.com/Hoixg/rikkaplus/tree/1.0) | 專案的主要開發分支。 |
| [`custom`](https://github.com/Hoixg/rikkaplus/tree/custom) | 歷史客製化分支，供既有使用者使用與功能參考。 |

`1.0` 是分支名稱，應用程式版本以安裝包標示為準。

## 開發

主要技術為 Kotlin、Jetpack Compose、Material 3 Expressive、Room 與 DataStore。

使用 Android Studio 開啟專案，依專案建構設定準備 Android SDK 和相應的 `app/google-services.json`。

問題與建議請在[本儲存庫 Issues](https://github.com/Hoixg/rikkaplus/issues)討論。

## 上游與授權條款

RikkaPlus 基於 [re-ovo](https://github.com/re-ovo) 建立的 [RikkaHub](https://github.com/rikkahub/rikkahub)。感謝原作者及貢獻者。

專案保留 [GNU Affero General Public License v3.0](LICENSE) 與原有著作權聲明。
