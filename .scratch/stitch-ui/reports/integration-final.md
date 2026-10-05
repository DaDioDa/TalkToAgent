# Stitch UI：整合交付與真人驗收

**三張票已實作、提交並整合；自動驗證與獨立雙軸審查完成，現在可進行 human validation。** 沒有將真實手機、Gemini、Windows 或光學 QR 說成已驗收。

## 版本與產物

- 分支：`feat/stitch-native-ui`，未 push、未發布 GitHub Issue／PR。
- 起點：`7fa329984ef4d933aa2318777f17b40c611a1593`。
- 最終程式提交：`3081abc346066aaca48c5a2cd402faefd257d290`；其後交付提交只更新文件與驗證紀錄。
- APK：[app-debug.apk](../../../app/build/outputs/apk/debug/app-debug.apk)，13,818,772 bytes。此建置產物留本地，不提交 binary APK。
- APK SHA-256：`D21D94782082BEDBA376E93859CF838651F9EB062A5851621AC96F63A1D24600`。
- 規格：[UI 還原規格](../../../docs/spec-stitch-ui.md)。各票狀態維持 `implementation-complete（human-validation-pending）`；混合真人驗收的條件不提前全勾。

| 範圍 | 提交／證據 |
| --- | --- |
| 規格、設計與票 | `62a2f47` |
| 01 語音首頁 | `b8cbb49`、`6938a1e`、`be3ca69`；[報告](ticket-01.md) |
| 02 辨識預覽／音量 | `da59520`；[報告](ticket-02.md) |
| 03 全 App 次頁 | `68785ab`；[報告](ticket-03.md) |
| 02／03 整合 | `ddd3fa4`；保留兩票意圖，沒有選一邊覆蓋另一邊 |
| 審查及矩陣修正 | `3081abc`；[修正報告](integration-fix.md) |

## 指揮官親自重跑

在最終程式版本執行 Gradle `--rerun-tasks`，81 項 tasks 實際執行；不是只採信代理摘要或 UP-TO-DATE。

| 檢查 | 結果 | 原始紀錄 |
| --- | --- | --- |
| JVM、debug APK、測試 APK、lint | JVM **62／62**；BUILD SUCCESSFUL；lint **0 errors／18 warnings** | [build](final-commander-build.txt) |
| 390×822dp、三鍵導覽、完整原生套件 | runner **OK (40 tests)** | [native](final-commander-native.txt) |
| 320×640dp、2.0 倍字體、手勢、真鍵盤；預覽／次頁／QR | runner **OK (19 tests)** | [matrix](final-commander-compact-font2.txt) |
| 相機權限在 runner 外撤銷後單獨驗證 | **OK (1 test)** | [camera denied](final-commander-camera-denied.txt) |

完整 suite 中相機已允許時，拒絕條件測試會 assumption skip，因此另跑獨立拒絕案例，沒有把 skip 計作該情境的通過證據。相同測試會在完整套件與矩陣重複出現，不將這些數字相加宣稱為不同測試數。

修正代理另外在同一程式版本完成手勢完整 **40 項**、19 項矩陣多次通過、鍵盤單檔連續 3 次通過；詳見修正報告及其原始紀錄。最初小視窗矩陣的失敗、QR 接線負向控制均保留，未改寫成從未失敗。

## 獨立審查

前輪：[整合審查](integration-review.md)。原 reviewer 工作階段已被 harness 回收，複查改由兩個新的獨立 reviewer 讀取前輪紀錄與修正 diff，沒有冒稱成功 resume。

### Standards：APPROVED

複查代理 `877f964e-8e9c-4df`：原分頁重複設定與 22dp／18dp 分歧已由共用 helper 解除；外部相機建構 seam 保留正式回呼及驗證，沒有新依賴。硬性違反 **0**、可證實新增問題 **0**、新增 smell **0**。

### Spec：APPROVED

複查代理 `4af8cccd-23ee-41d`：QR 五項測試穿過正式註冊 callback、Activity result、主畫面接收、pin 檢查及 loopback transport；切斷回傳或接收均產生 RED。原覆蓋缺口已解除。小視窗修正保留原斷言、真鍵盤及正常捲動，沒有縮弱要求。新增 Spec finding **0**。

兩位複查均唯讀、不操作裝置；上節 runtime 是指揮官另行執行。兩軸分別通過，不以某一軸抵銷另一軸。

## 模型與隔離核對

[model-audit.json](model-audit.json) 逐一核對 **3 位票實作者＋1 位修正者＋4 位 reviewer** 的完整執行 JSONL metadata。所有 assistant 訊息均是：

```text
provider = openai-codex
model = gpt-6.1-sol
thinkingLevel = medium
```

不只檢查派工參數或代理自述。文件收尾另外曾呼叫專用 committer；雖明確指定相同配置，harness 實際啟動 Luna／low，代理在第一步核對後停止，沒有 stage／commit。文件提交改用通用代理，仍須先核實 Sol 6.1／medium 才可 stage／commit；這次被攔截的配置不符不冒稱通過上述八位實作／審查代理的稽核。

01 先完成，02／03 再以隔離工作樹及獨立模擬器平行實作。工作樹已由 harness 清理，`git worktree list` 最後只剩主目錄；任務建立的兩台模擬器已停止，AVD 保留可再次啟動。

六份事前存在的原型／舊規格修改以 SHA-256 確認逐 byte 未變；新出現的 `.idea/` 也未納入提交。原有 dirty files 刻意保留，不宣稱整個使用者工作樹乾淨。

## 畫面與已知差異

最終修正版本的代表畫面：

- [正式首頁](../screenshots/integration-fix/home-tabs.png)
- [正式裝置連線](../screenshots/integration-fix/connection-tabs.png)
- [小視窗／2 倍字體／真鍵盤](../screenshots/integration-fix/compact-font2-keyboard.png)
- [小視窗辨識預覽／音量](../screenshots/integration-fix/compact-font2-preview.png)

各票報告另保存完整狀態及次頁證據；它們也記錄當時的版本差異。預覽／音量狀態圖使用可控外部回呼，不是實際雲端或物理 mic。QR UI 圖保留安全旗標且不包含相機／邀請像素，不能用來證明光學掃描。

按確認範圍移除頭像、模式切換及假資訊；採本機字體、原創向量及 Android 真實安全區，而非逐像素複製網頁。長原因、大字體及活動狀態的資訊區可捲動，主操作與取消保持可到達。這些差異留給真人視覺／手感確認，不包裝成零差異還原。

## Human validation 清單

1. **安裝／升級**：在原支援手機安裝上述 APK，確認既有設定、金鑰與各通道應用授權保留。若簽章不相容，先停止；不要用卸載或清資料來繞過升級驗收。
2. **真實連線**：掃描 Windows 的有效／失效 QR，驗證相機權限及取消；分別驗證系統藍牙配對、應用授權取得／恢復／撤銷與 Wi-Fi，不混為同一件事。
3. **說話到貼上**：設定真實 Gemini 金鑰、允許麥克風、輸入就緒後點按開始；確認真實預覽與音量、再次點按結束後只貼上有效定稿一次。電腦仍需將游標放在目標欄位。
4. **取消／期限／離開**：準備中及收音中取消不貼上；45 秒包含準備、到限正常收尾；切 App／鎖屏在未送出前取消。驗真實輕震、亮屏與單手誤觸，不以模擬器取代手感。
5. **不明結果**：傳送後斷線／離開時，無可信結果須提示檢查電腦，不自動補送；「貼上操作完成」不代表目標程式已接受文字或執行指令。
6. **可及性與次頁**：真機大字體、TalkBack、長定稿、鍵盤、剪貼簿、金鑰與診斷草稿保留，以及整體視覺還原程度。

上述是真人驗收，不是尚未寫完的已知程式工作。若發現問題，以目前程式提交及步驟／狀態／截圖回報，勿把過程中的舊 APK 當本次交付。
