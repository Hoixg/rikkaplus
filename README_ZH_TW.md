<div align="center">
  <img src="docs/icon.png" alt="RikkaPlus 圖示" width="100" />
  <h1>RikkaPlus · 1.0</h1>
  <p>以最新 RikkaHub 原版為基礎，重新建構 RikkaPlus。</p>

[English](README.md) | [简体中文](README_ZH_CN.md) | 繁體中文
</div>

## 分支介紹

`1.0` 是 [RikkaPlus](https://github.com/Hoixg/rikkaplus) 的新重構分支，由 Hoixg 維護。

本分支從上游 [RikkaHub](https://github.com/rikkahub/rikkahub) 原版出發，重新整理並實作 `custom` 中累積的客製化功能，逐步處理早期開發留下的技術債務、功能耦合與資料結構負擔，讓後續開發與同步上游更容易維護。

## 分支分工

| 分支 | 定位 |
| --- | --- |
| [`1.0`](https://github.com/Hoixg/rikkaplus/tree/1.0) | 新重構開發分支，以原版為基礎重新組織與實作客製化能力。 |
| [`custom`](https://github.com/Hoixg/rikkaplus/tree/custom) | 現有客製化版本，保留供日常使用與功能對照。 |

`1.0` 是分支名稱，目前的上游應用程式基線為 **RikkaHub 3.0.0**。

## 重構目標

- 逐項整理客製化功能，明確定義需要保留、簡化與重新設計的內容。
- 釐清功能邏輯、聊天生成、介面與資料儲存之間的職責。
- 依實際使用情況整理廢棄資料表、冗餘欄位與歷史相容邏輯。
- 統一 Material 3 Expressive 介面與互動。
- 逐步實作並驗證各項功能，讓變更便於審查，也便於持續同步上游。

多 Key、排程任務、子代理、生圖審批與工具互動等客製化能力將納入評估，具體保留範圍與實作進度以後續開發紀錄為準。

## 目前狀態

**重構仍處於起步階段。** 目前程式碼以上游原版為基礎，客製化功能尚未在本分支完成重建。

舊資料相容、資料庫遷移、匯入及覆蓋升級方案尚未確定，後續另行討論。

現有客製化安裝包請見 [RikkaPlus Releases](https://github.com/Hoixg/rikkaplus/releases)，並依對應的發佈說明確認版本所屬分支。

### 已完成的整理

- 移除 Web 伺服器、瀏覽器聊天介面、相關背景服務與前端建構相依套件。

## 自動建構與雲端發佈

儲存庫預設開發分支為 `1.0`。

- **每日自動建構**：北京時間每天 02:00、17:00 檢查更新，沒有新提交時略過。簽章安裝包發佈至獨立的 [1.0-nightly](https://github.com/Hoixg/rikkaplus/releases/tag/1.0-nightly)。
- **手動雲端建構**：進入 [Actions](https://github.com/Hoixg/rikkaplus/actions/workflows/release.yml)，選擇 `1.0` 分支及 `release`／`debug` 類型，完成後下載建構產物。手動建構不發佈新版本。
- **版本發佈**：推送與應用程式版本相符的附註標籤，例如 `1.0-v3.0.1`，觸發簽章建構與發佈。與舊 custom 版本的標籤分開管理。
- **更新說明**：正式發佈優先讀取 `docs/releases/<應用程式版本號>.md`，參考[說明範本](docs/releases/TEMPLATE.md)；其他建構依分類整理提交標題與內文。

雲端建構沿用儲存庫密鑰 `KEY_BASE64`、`SIGNING_CONFIG` 與 `GOOGLE_SERVICES_JSON`，亦可為本分支另設優先使用的 `GOOGLE_SERVICES_JSON_1_0`。沿用既有 Firebase 專案設定時，建構會比對目前套件名稱；使用 Firebase 服務時，應透過分支專屬密鑰提供已註冊的 Android 用戶端設定。

## 開發

主要技術為 Kotlin、Jetpack Compose、Material 3 Expressive、Room 與 DataStore。

使用 Android Studio 開啟專案，依上游建構設定準備環境。Android 建構需要相應的 `app/google-services.json` 與本機 SDK 設定。

本分支的功能規劃與問題請在[本儲存庫](https://github.com/Hoixg/rikkaplus/issues)討論。

## 上游與授權條款

RikkaPlus 基於 [re-ovo](https://github.com/re-ovo) 建立的 [RikkaHub](https://github.com/rikkahub/rikkahub)。感謝原作者及貢獻者。

專案保留 [GNU Affero General Public License v3.0](LICENSE) 與原有著作權聲明。
