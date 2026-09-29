# Android 按住說話（Issue #3）

> 以下描述原有 Wi-Fi 語音輸入流程。App 現已加入手動選擇藍牙、已配對裝置及 `connectedDevice` 前景連線服務的**實驗性切片**；已在 SM-F7410 與 Windows 完成一次使用者確認的語音轉錄／藍牙傳送／貼上操作；Windows 系統藍牙需先開啟。鎖屏 30 分鐘、重連、鎖定電腦拒絕輸入及其他裝置仍未完成實機驗收，請勿將建置與單次貼上成功當作全部條件已通過。設計範圍及後續驗收見 [藍牙增量規格](spec-bluetooth-input.md)。

若手機已有不同簽章的同名 App，不要直接卸載以免遺失設定與 Gemini 金鑰。可從專案根目錄執行 `./gradlew -PbluetoothTestApp=true :app:assembleDebug`，將產生的 `app/build/outputs/apk/debug/app-debug.apk` 安裝為獨立的 `com.example.talktoagent.bluetoothdebug` 測試 App；此版本與原 App 的資料互不共用，須另行設定金鑰。若要跑裝置測試，先連接已授權 ADB 的手機，再執行 `./gradlew -PbluetoothTestApp=true :app:connectedDebugAndroidTest --no-configuration-cache`。未加 `-PbluetoothTestApp=true` 的一般建置仍使用原套件名。

此切片沿用 Issue #2 的 Receiver 與一次性已驗證 WebSocket 連線；`docs/windows-receiver.md` 的手動文字示範仍可用。先在手機設定 Gemini 個人 API key（輸入後按「儲存」，欄位會清空；留空儲存會清除金鑰），再輸入 Receiver 的 IP、埠與當次配對碼並按「驗證 Windows Receiver」。金鑰僅在手機以 Android Keystore AES-GCM 加密儲存，不送給 Windows Receiver，也不寫入日誌／APK 原始常數／備份；重裝後需要重新輸入。手機直接透過 TLS 連接 Gemini。

首次按說話鍵會要求麥克風權限；授權彈窗結束後**重新**按住才開始收音。被拒絕則不連 Gemini、不收音、不傳送。按住鍵期間 Gemini 3.5 Transcribe Live SMART 收取 16 kHz mono PCM；放開後送出 `activityEnd`，累積放開前後收到的真實定稿片段；在收尾訊號送出且最後一段定稿後安靜約 1 秒，就合併送一次，最長等待 8 秒。暫定稿絕不送到 Receiver；8 秒內沒有可用定稿、收音／連線錯誤或最後一段定稿尚未安靜足夠時間時不貼上，也不自動重送。Gemini 未保證所有定稿片段的收尾順序，因此極晚片段仍可能遺漏，需在實機驗證；45 秒收音上限避免超過 Receiver 的 60 秒等待。一次貼上後請重新驗證 Receiver。手機的「Receiver 已確認」不保證 Windows 目標應用實際顯示文字；結果不明時先檢查目標欄位。

Gemini 的轉錄定稿在手機送出前轉為繁體字形，英文、數字與標點不做字形轉換；轉換失敗或結果超限時不貼上。畫面左上角的單一燈號與文字只在 Receiver 已驗證、金鑰可用、麥克風已授權且沒有正在進行的口述或已知故障時顯示「輸入就緒」；這不代表閒置時已連接 Gemini，也不保證 Windows 目標應用已顯示貼上的字。故障時保持非綠燈並顯示原因，重連不補貼舊定稿。最近一次口述定稿只在目前畫面暫留，可按「手動複製最近定稿」自行放入剪貼簿；離開畫面後不儲存逐字稿歷史。用於可信任的家庭 LAN：Receiver 的 `ws://` 連線未加密，不能在公共／辦公網路或敏感資訊上使用。

實機驗收尚需在 SM-F7410 + Windows Receiver 進行：短句、停頓及多段定稿、放開收尾、英文混用、權限拒絕、錯誤 key／網路、焦點切換與剪貼簿行為。已驗證模型／端點／`SMART`／`activityStart`/`activityEnd` 的官方依據：[Gemini Live transcription](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe)。
