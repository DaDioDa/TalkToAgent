# Stitch UI：本地實作票

本組票只存本地，未建立 GitHub Issue；切票不代表 App 已實作或驗收。

## 接手資料

- 實作前讀 [UI 還原規格](../../docs/spec-stitch-ui.md)，以其確認範圍及來源優先順序為準。
- 使用領域詞彙時讀 [CONTEXT.md](../../CONTEXT.md)；涉及原有語音或連線行為時，沿規格中的既有規格／ADR 連結取得約束。
- 還原主頁時以 [設計截圖](../../stitch_talktoagent/screen.png) 為外觀基準，[互動稿](../../stitch_talktoagent/code.html) 補充結構；設計缺圖的次頁參考 [設計規範](../../stitch_talktoagent/DESIGN.md)。示範資訊與互動的取捨以 UI 還原規格為準。

## 票

1. [還原語音首頁與既有操作](issues/01-voice-home.md)
2. [接入真實辨識預覽與音量波形](issues/02-live-preview-waveform.md)
3. [統一裝置連線與次要頁面風格](issues/03-secondary-screens.md)

每張票的 **Blocked by** 是依賴的唯一來源；只有前置票全部完成的票才可接手。**Status: ready-for-agent** 表示需求已具備，並不免除前置條件。各票包含自己的測試與原生驗收，不另留一張最後才補測試的票。
