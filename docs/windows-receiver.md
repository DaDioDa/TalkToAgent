# Windows Receiver：QR 邀請與持久應用授權

手機在 App 內掃描 Windows 終端 QR，取得指定通道的應用授權。Wi-Fi 每個連線最多接受一段轉錄定稿，貼上後關閉；藍牙保持工作階段。兩者都不保存逐字稿、不自動 Enter、不補送文字。

## 安全限制

- Wi-Fi 只能用於彼此可連線的可信任私人家庭區網。`ws://` **不加密文字**，QR、簽章授權與藍牙安全設定都不會把 Wi-Fi 變成 TLS；不承諾抵禦主動中間人。不要在公司／公共網路或傳送敏感文字時使用。
- 有效 QR 持有人可能搶先取得應用授權。不要分享 QR、截圖或將 Receiver 主控台輸出重新導向到日誌。QR 秘密只供短效邀請，不是永久配對碼。
- 系統藍牙配對不等於應用授權；一台 Receiver 只授權一支手機，Wi-Fi／藍牙需各自掃碼。原手機可追加通道，更換手機必須先在 Windows 撤銷。
- 轉錄定稿最多 4096 bytes UTF-8，frame 最多 32768 bytes；空白、NUL、無效 Unicode、非法欄位或超限文字不會貼上。
- Receiver 替換 Windows 剪貼簿並對目前焦點送 Ctrl+V，不會還原剪貼簿。「貼上操作完成」只代表剪貼簿及貼上動作已發出，不代表目標程式已接受或執行。
- 「輸入結果不明」時先檢查電腦，不要立刻重送。重連不會補貼旧文字。

## 安裝與啟動（PowerShell）

在專案根目錄：

```powershell
cd windows_receiver
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe receiver.py
```

使用 Python 3.10 以上。第三方相依包括 WebSocket、成熟 QR 與密碼學套件；不需要 Node runtime 或額外管理 QR 工具。

Wi-Fi 預設 port 8765，可用 `--port 8766` 改變。候選 IPv4 有多個時依主控台提示選擇一個；QR 只有單一目的位址，手機不自動試連其他網卡。Windows 防火牆僅允許可信任私人網路。

藍牙啟動：

```powershell
.\.venv\Scripts\python.exe receiver.py --bluetooth
```

先開啟 Windows 系統藍牙。單一可用 radio 直接使用，多個時按主控台提示選擇；沒有實體 radio 或只有無效地址時停止，不顯示不可用 QR。QR、listener 與 SDP 綁同一介面，安全 authentication／encryption 設定或 SDP 註冊失敗就停止，不能關閉安全選項作為修復。

保持主控台開啟，Ctrl+C 停止。停止會使未使用邀請失效，但不撤銷已持久保存的應用授權。

## 邀請操作

依主控台提示使用：

- `new`：明確產生當前通道的新邀請，立即使舊的未使用邀請失效；不必重啟。
- `show`：重顯目前有效邀請，不延長原期限。若終端太窄，調大視窗、調整字型／縮放後重顯示；保留 QR 四周空白。
- `revoke`：撤銷原手機，兩通道授權及現有連線一併失效。確認成功後才能為不同手機產生新邀請。

邀請由 Receiver 判定 10 分鐘期限，僅成功授權時消耗一次；連線未成功可在期限內重掃／重試。過期、已消耗或刷新後的邀請無法首次授權。不要以重顯示當作刷新，也沒有自動刷新。

Windows／Android 各自保存安全身分；重連不傳長期私鑰或舊邀請秘密。舊版只記住 MAC 的資料不會自動轉為授權，升級需重新掃碼。授權儲存不可讀時拒絕授權／輸入，不會默默重建成未授權。

## 手機操作與文字示範

1. 建置並安裝 App：

   ```powershell
   .\gradlew.bat :app:assembleDebug
   android install --apks=app\build\outputs\apk\debug\app-debug.apk
   ```

   手機若已安裝不同簽章的 App，不要直接卸載而遺失設定；可用 `-PbluetoothTestApp=true` 建置獨立測試套件（見 [Android 語音說明](android-voice.md)）。
2. 在 App 內按掃描，允許相機權限，對準終端 QR。沒有手動輸入 IP／port／授權碼、手選藍牙裝置或 QR 圖片備援。相機／附近裝置／通知權限拒絕時依提示到設定修復。
3. 藍牙尚未配對時依 Android 與 Windows 的**系統**確認操作；成功且邀請仍有效後續接。不再要求 Windows 主控台 `yes`。取消則停止並重掃；若配對後邀請過期，在 Windows 產生新邀請，不解除已完成的系統配對。
4. 授權成功後將游標放在 Windows 記事本空白文件，再傳一次測試文字。應只貼一次、無額外 Enter。僅取得 Receiver 授權不等於「輸入就緒」：語音還需要 Gemini 金鑰及麥克風權限。
5. App／Receiver 重啟後，App 顯示原電腦與已授權通道，由使用者按「連線」恢復，不用重掃，也不在開啟 App 時自動連線。Wi-Fi 每次文字後需再次按連線；藍牙工作階段斷線保留退避重連，手動中斷後不自動連線。
6. 連線中不能掃新邀請，須先結束口述及主動中斷。已送文字無法確認時仍保留「輸入結果不明」，不能因切換而清除或補送。

成功授權回覆遺失時，手機可用已同步保存的 pending 目標與原私鑰恢復；若 Receiver 未保存授權則需要重掃。不會讓第二支手機重用消耗過的邀請。畫面區分已授權目標與 pending，正常「連線」優先使用原授權，另外可按「恢復上次未完成授權」嘗試 pending；失敗的位址更新不會遮蔽原授權目標。

系統藍牙配對等待在旋轉／折疊造成畫面重建時保留於記憶體，重建後重新查指定裝置的實際系統配對狀態並續接。若 App 程序結束，未完成邀請不恢復、不在冷啟動自動連線，提示產生新邀請重掃；QR 秘密不寫入畫面狀態或磁碟。

電腦 IP 改變時，Receiver 在新位址產生更新邀請，原手機掃描並驗證雙方身分後更新目標；不撤銷原授權、不自動網路探索。

## 測試與驗收界線

```powershell
.\windows_receiver\.venv\Scripts\python.exe -m unittest discover -s windows_receiver -p "test_*.py" -v
.\gradlew.bat :app:compileDebugJavaWithJavac :app:testDebugUnitTest
.\windows_receiver\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

以上從專案根目錄執行。Receiver 測試使用真實本機 WebSocket、可控 framed RFCOMM 與記錄用貼上動作，不操作目前桌面。Android 測試在掃描結果到授權／連線的行為邊界驗證。`tests` 的跨平台測試需要 JDK，並在 Gradle 解析 Gson 後執行；它以實際 Java coordinator／JCA 對接 Python Receiver／cryptography 的真實本機 WebSocket，檢查首次授權、成功回覆遺失恢復及明確定稿，不替代 Android Keystore／相機或 RFCOMM 實機驗收。跨平台契約見 [授權協定](authorization-protocol.md) 與共用向量。

舊版曾在已配對 SM-F7410 與 Windows 完成一次使用者確認的語音貼上，**不能當作新 QR 流程的實機證據**。新流程的終端字型／縮放、實際 URI 長度、未配對藍牙雙端確認／續接、取消／過期、兩端重啟、撤銷及鎖定拒絕仍需逐項實機記錄。硬體驗收清單見 [QR 驗收](qr-acceptance.md)。

Windows RFCOMM 伺服器用 `BT_PORT_ANY` 要求通道（不是 client-only port 0）；`SOCKADDR_BTH` 使用 Windows 30-byte packed 配置，SDP 退出時移除註冊。這些既有 API 修正不保證所有 radio／驅動及未配對續接都可用。
