# Android 點按語音輸入（Issue #13）

> 新入口與計時／生命週期程式已實作；本次沒有已連接的 ADB 裝置，尚未驗證新流程的真機／端到端行為。自動測試及未驗證清單見 [#13 交付紀錄](issue13-native-production.md)。以下舊版實機證據不代表新流程已驗收。

> 連線設定改為 App 內掃描短效 QR 邀請，並安全保存持久應用授權；不再手動輸入 IP／port／臨時碼或選擇已配對裝置。藍牙保留 `connectedDevice` 前景服務。舊版曾在已配對 SM-F7410 與 Windows 完成一次使用者確認的語音貼上；此證據不涵蓋新的 QR／未配對續接。新流程與既有鎖屏 30 分鐘等未完成的實機項目，不能以建置或 mock 成功替代，見 [QR 驗收](qr-acceptance.md) 及 [藍牙增量規格](spec-bluetooth-input.md)。

若手機已有不同簽章的同名 App，不要直接卸載以免遺失設定與 Gemini 金鑰。可從專案根目錄執行 `./gradlew -PbluetoothTestApp=true :app:assembleDebug`，將產生的 `app/build/outputs/apk/debug/app-debug.apk` 安裝為獨立的 `com.example.talktoagent.bluetoothdebug` 測試 App；此版本與原 App 的資料互不共用，須另行設定金鑰。若要跑裝置測試，先連接已授權 ADB 的手機，再執行 `./gradlew -PbluetoothTestApp=true :app:connectedDebugAndroidTest --no-configuration-cache`。未加 `-PbluetoothTestApp=true` 的一般建置仍使用原套件名。

Wi-Fi 保留每連線傳送一次文字的行為；連線及手動文字示範見 [Windows Receiver](windows-receiver.md)。先在手機設定 Gemini 個人 API key（輸入後按「儲存」，欄位會清空；留空儲存會清除金鑰），再在 App 內掃描 Windows 終端邀請。藍牙未系統配對時依兩端系統確認完成後續接；取消停止並重掃，過期需新邀請。App／Receiver 重啟後可選已授權通道按「連線」，不會自動連線。金鑰僅在手機以 Android Keystore AES-GCM 加密儲存，不送給 Windows Receiver，也不寫入日誌／APK 原始常數／備份；重裝後需要重新輸入。手機直接透過 TLS 連接 Gemini。

正式入口使用 Native C 原生畫面。連線管理提供 QR、既有／未完成應用授權恢復、Wi-Fi／藍牙通道及主動中斷；設定提供安全金鑰管理、麥克風權限修復及診斷／手動測試。手動文字不再常駐首頁。

先在設定允許麥克風權限；權限修復返回後仍須**主動點按開始**，不會自動收音。點一下「開始輸入」先進入準備，真正麥克風啟動才顯示收音中並輕震、保持亮屏；再點一下「結束並送出」正常結束並輕震。Gemini 3.5 Transcribe Live SMART 收取 16 kHz mono PCM；結束後送出 `activityEnd`，累積結束前後收到的真實定稿片段；在收尾訊號送出且最後一段定稿後安靜約 1 秒，就合併送一次，最長等待 8 秒。暫定稿絕不送到 Receiver；8 秒內沒有可用定稿、收音／連線錯誤或最後一段定稿尚未安靜足夠時間時不貼上，也不自動重送。Gemini 未保證所有定稿片段的收尾順序，因此極晚片段仍可能遺漏，需在實機驗證。45 秒期限從點按開始起算，包含準備時間；剩 5 秒提示一次短促雙震與文字預警，到限與手動結束沿用同一有效定稿傳送路徑，不再丟棄整段。初始化仍以 10 秒失敗期限中止。準備／收音可按底部全寬取消列；Home、切 App、退出或鎖屏會丟棄尚未送出內容，包括等待定稿階段。取消不是正常提交，晚回呼不能補送；已提交內容不能撤回，離開時保留可信結果或顯示「輸入結果不明」。亮屏只在收音期間生效，不修改系統設定，沒有提示音。Wi-Fi 一次貼上後請再按「連線」；藍牙保持工作階段。連線中不能掃描新邀請，必須先結束口述及主動中斷；切換不補送，也不抹除「輸入結果不明」。手機的「Receiver 已確認」不保證 Windows 目標應用實際顯示文字；結果不明時先檢查目標欄位。

Gemini 的轉錄定稿在手機送出前轉為繁體字形，英文、數字與標點不做字形轉換；轉換失敗或結果超限時不貼上。首頁只在 Receiver 已驗證、金鑰可用、麥克風已授權且沒有正在進行的口述或已知故障時顯示「輸入就緒」；這不代表閒置時已連接 Gemini，也不保證 Windows 目標應用已顯示貼上的字。故障顯示原因及修復入口，重連不補貼舊定稿。就緒與上一段結果獨立，結果不明警告不因重連消失；條件恢復後主動開始新段才替換警告，不需額外確認。藍牙保持工作階段，但斷線後須使用者主動恢復，不自動重連／切換／重送。最近一次定稿只在 Activity 記憶體暫留，預設隱藏；可從「最近定稿」查看或按「複製定稿」，不會再次傳送，不儲存逐字稿歷史。用於可信任的家庭 LAN：Receiver 的 `ws://` 連線未加密，不能在公共／辦公網路或敏感資訊上使用。

實機驗收尚需在 SM-F7410 + Windows Receiver 進行：短句、停頓及多段定稿、手動／45 秒到限收尾、取消／Home／切 App／鎖屏、震動與亮屏、英文混用、權限拒絕、錯誤 key／網路、焦點切換與剪貼簿行為。已驗證模型／端點／`SMART`／`activityStart`/`activityEnd` 的官方依據：[Gemini Live transcription](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe)。
