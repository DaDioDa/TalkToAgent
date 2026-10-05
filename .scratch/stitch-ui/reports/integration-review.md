# Stitch 整合審查

審查固定點：`7fa329984ef4d933aa2318777f17b40c611a1593...ddd3fa476d4fd200c6130bef23515db55de8bffb`。兩軸以獨立 Sol 6.1／medium 代理平行執行，以下保留各軸結論，不將維護性建議與需求缺口混合排名。

## Standards

審查代理：`5aaf662a-45f5-4ed`。結論 **APPROVED，1 項非阻擋改善**。

- 硬性規範違反：0。
- 可證實新增正確性／安全性問題：0。
- 判斷性 smell：`NativeCScreen` 首頁與裝置頁重複分頁樣式設定，首頁圖示為 22dp，裝置頁沒有沿用。建議在原類別抽出小型共用樣式方法，不重做導覽架構。
- 審查代理以既有編譯產物執行 61 項 JUnit；這不是該固定點重新建置的證據。父方另有完整建置及測試紀錄。

## Spec

審查代理：`81928b7a-17b4-4bb`。結論 **NEEDS CHANGES，1 項部分需求**。

- QR 成功／無效結果缺少正式接線自動回歸測試。現有原生測試驗證取消與無相機權限，解析器測試不能證明解碼回呼、`RESULT_OK` 與主畫面接收路徑有接通。
- 規格依據：Ticket 03 要求 QR 掃描結果、無效邀請沿原流程運作，以及 QR 取消／失敗的原生流程回歸。
- 最小補強：透過受控外部解碼結果測試有效／無效邀請、回傳／錯誤提示，以及既有應用授權不被錯誤改寫；不依賴實體相機或 Windows。
- 已確認 scope creep：0；已確認正式功能實作錯誤：0。實機／雲端／人工輔具驗收仍另列，不算此處缺陷。

## 指揮官的整合驗證

在 `ddd3fa4` 的正式合併程式上重新執行：

- JVM：62／62；debug APK、測試 APK 與 lint 建置成功；lint 0 errors、18 warnings。
- 三鍵模式完整原生套件：35 項，runner OK；相機拒絕條件另 revoke 後獨立 1 項 OK，不把 conditional skip 當通過。
- 手勢模式完整原生套件：35 項，runner OK。
- 額外 320dp／640dp 高／fontScale 2.0，執行預覽與次頁兩個套件：14 項中 2 個失敗，不能宣稱矩陣全部通過。
  - 預覽測試沒有先捲動便要求文字已顯示；規格允許資訊區捲動，需在正確可見性前提下驗證局部更新與語意。
  - 金鑰測試點擊欄位後立即 replaceText，IME 轉場中欄位一度不可見。相同尺寸／字體單檔重跑通過，需釐清 UI 可用性與測試時序並使回歸穩定，不能只以重跑綠燈結案。
- 正式 MainActivity 啟動與設定導覽已用官方 CLI layout／screen 實際檢查，未放入假金鑰或連真 Windows。

原始本地 log：`integration-merged-build.txt`、`integration-merged-native.txt`、`integration-camera-denied.txt`、`integration-gesture-native.txt`、`integration-compact-font2.txt`、`integration-keyboard-font2-repro.txt`。實際模型／effort另由父方核對各代理完整 JSONL 的 model/thinkingLevel，非僅採信自述。

## 後續處理

上述 1 個需求缺口、1 個小型樣式整理及矩陣測試問題已由同配置修正代理在 `3081abc` 處理。原 reviewer 工作階段被 harness 回收後，另派兩位同配置獨立 reviewer 複查，Standards 與 Spec 均為 APPROVED；指揮官再次執行的結果見 [整合交付報告](integration-final.md)。本文件保留當時審查結果，不回寫成「從未發現問題」。
