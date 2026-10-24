<div align="center">
  <img src="docs/icon.png" alt="RikkaPlus 圖示" width="100" />
  <h1>RikkaPlus · 1.0</h1>
  <p>RikkaPlus 的主要開發與維護分支。</p>

[English](README.md) | [简体中文](README_ZH_CN.md) | 繁體中文
</div>

## 分支定位

`1.0` 是 RikkaPlus 的主要開發分支，基於 [RikkaHub](https://github.com/rikkahub/rikkahub) 上游，圍繞目前架構重新整理專案的客製化功能，作為後續持續開發的基礎。

這個分支以清楚的程式碼結構、可維護的功能實作與一致的互動體驗為主要方向。客製化功能依本分支的需求重新設計，並在維護過程中清理廢棄程式碼、冗餘相依項與不必要的資料邏輯。

## 維護方向

- 持續跟進上游開發，依本分支定位選擇和整合變更。
- 優先重用現有架構、元件與工具，控制重複實作和維護成本。
- 圍繞本分支設計客製化功能及其資料結構，獨立維護套件名稱、簽章與發佈流程。

## 分支關係

| 分支 | 定位 |
| --- | --- |
| [`1.0`](https://github.com/Hoixg/rikkaplus/tree/1.0) | 目前主要開發與維護分支，功能開發、上游整合和版本發佈在此進行。 |
| [`custom`](https://github.com/Hoixg/rikkaplus/tree/custom) | 歷史客製版存檔，保留於 GitHub，停止維護。 |

`1.0` 是分支名稱，應用程式版本以發佈紀錄標示為準。

## 取得與回報

[發佈版本](https://github.com/Hoixg/rikkaplus/releases) · [每日建構](https://github.com/Hoixg/rikkaplus/releases/tag/1.0-nightly) · [問題回報](https://github.com/Hoixg/rikkaplus/issues)

## 致謝與授權條款

專案基於 [re-ovo](https://github.com/re-ovo) 與貢獻者開發的 [RikkaHub](https://github.com/rikkahub/rikkahub)。元件來源說明見 [Shizuku 文件](docs/shizuku.md)與[終端模組說明](terminal/README.md)。

專案採用 [GNU Affero General Public License v3.0](LICENSE)，保留上游著作權聲明。
