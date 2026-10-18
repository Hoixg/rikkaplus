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

## 開發

主要技術為 Kotlin、Jetpack Compose、Material 3 Expressive、Room 與 DataStore。

使用 Android Studio 開啟專案，依上游建構設定準備環境。建構 Web 模組另需 Node.js 與 pnpm；Android 建構需要相應的 `app/google-services.json` 與本機 SDK 設定。

本分支的功能規劃與問題請在[本儲存庫](https://github.com/Hoixg/rikkaplus/issues)討論。

## 上游與授權條款

RikkaPlus 基於 [re-ovo](https://github.com/re-ovo) 建立的 [RikkaHub](https://github.com/rikkahub/rikkahub)。感謝原作者及貢獻者。

專案保留 [GNU Affero General Public License v3.0](LICENSE) 與原有著作權聲明。
