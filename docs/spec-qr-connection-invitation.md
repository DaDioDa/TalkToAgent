# 規格：QR 連線邀請（Issue #9）

狀態：使用者已確認產品需求與驗收要求；藍牙 API／文件核對已完成。[Agent brief 已發布](https://github.com/DaDioDa/TalkToAgent/issues/9#issuecomment-5902083591)，Issue 已標記 enhancement、ready-for-agent。未開始實作或實機驗收。

來源：https://github.com/DaDioDa/TalkToAgent/issues/9

本文件記錄 grilling 已確認的決策及待決問題，不代表現有能力。領域詞彙見 [CONTEXT.md](../CONTEXT.md)，既有藍牙限制見 [ADR-0001](adr/0001-direct-bluetooth-input.md) 與 [藍牙規格](spec-bluetooth-input.md)。

## 已確認

- Wi-Fi 與藍牙都提供 QR 連線邀請。Receiver 依目前啟動模式產生邀請，每份邀請只有一種通道，不同時啟用兩通道、不自動選擇或跨通道降級。
- Android 提供 App 內掃描入口，掃描有效邀請後立即嘗試連線與驗證，不增加目標確認步驟。不新增外部相機 Deep Link 入口。
- 尚未完成系統藍牙配對時，由 App 開啟系統配對流程，成功後自動續接邀請。這不代表繞過系統確認或安全配對；API 路徑已有文件依據，實機續接及失敗處理仍需驗證。
- 邀請使用版本化的 TalkToAgent 專用 URI，嚴格驗證版本、欄位與通道；具體格式待工程設計。
- 邀請有效期為 10 分鐘，成功取得應用授權後才消耗；尚未成功的嘗試可在期限內重試。成功回覆遺失的處理仍未定案。
- 兩端保存獨立於邀請的持久應用授權，直到使用者撤銷；App／Receiver 重啟後可供重連原電腦驗證使用。不沿用已消耗的邀請秘密作為重連憑證，也不因重啟自動開始輸入。
- 成功驗證 QR 邀請取代藍牙首次應用授權的 Windows 主控台 yes；系統藍牙配對確認仍保留。能取得有效邀請的人可能搶先授權。
- Receiver 提供主控台操作產生新邀請，不需重啟；產生時立即作廢舊的未使用邀請。不自動輪替或持續開放授權。更換手機前，必須先在 Windows 明確撤銷原手機並終止其連線，才可產生給新手機的邀請。
- 一台 Receiver 只授權一支手機；Wi-Fi 與藍牙各自需要掃碼授權，不能因另一通道已授權而直接取得權限。同一手機識別及追加通道邀請的驗證仍待工程設計。
- App 連線中不可掃描新的邀請。使用者須先結束本次輸入並主動中斷；已送出但無法確認的結果仍須標示「輸入結果不明」，不得因中斷或切換而補送。
- 完全移除 Android 原有手動輸入 IP、port、授權碼及選擇藍牙裝置的連線介面，不保留進階手動備援。
- Receiver 使用現成 Python QR 套件，不自行實作 QR 編碼器，不引入 Node.js。優先驗證 Segno 的標準 QR 終端輸出；套件版本及最終選用須經相容性與掃描驗證。

- 系統藍牙配對取消時停止本次嘗試，重新掃描才重試；邀請過期則要求 Windows 產生新邀請。已完成的系統藍牙配對不因此撤銷。
- App 重啟後顯示已授權電腦與通道，由使用者按「連線」恢復，不新增開機或開啟 App 即自動連線。
- 相機權限拒絕或終端 QR 無法辨識時，清楚阻止建立新授權，導引開啟權限、調整終端寬度／縮放或重新顯示 QR。不新增手動輸入或 QR 圖片備援。
- Wi-Fi 有多個候選位址時，在 Windows Receiver 主控台由使用者選擇，再產生單一目的位址的邀請；手機不自動試連多個位址。

- 原電腦 Wi-Fi 位址變動時，可為原授權手機產生短效單次的位址更新邀請。手機中斷後掃碼，驗證仍為原電腦及原手機才更新；不撤銷原有授權、不開放另一支手機、不增加自動探索。
- 本次不加入 Wi-Fi TLS，維持可信任家庭網路限定及警告。重連驗證不得直接傳送可長期重用的秘密；這不保護未加密的文字，也不構成抵禦主動中間人的承諾。

## 藍牙技術核對結果

- Android 可由已知 MAC 取得 `BluetoothDevice`，不必先進行裝置搜尋。`createBond()` 是非同步操作，須針對邀請指定的裝置等待 `ACTION_BOND_STATE_CHANGED` 確認 `BOND_BONDED`，不能將呼叫成功當成配對完成。
- Android 12+ 配對／連線需執行期 `BLUETOOTH_CONNECT`。僅因使用已知 MAC 連線，不必新增 `BLUETOOTH_SCAN` 或定位權限；若實作另呼叫 discovery／cancelDiscovery 等 API，須逐項核對權限。
- 系統負責必要配對確認，不能承諾兩端完全零操作。Windows 配對提示及可配對狀態須實機驗證。
- 目前 Receiver 綁定 wildcard 藍牙位址；不能假定連線前 `getsockname()` 一定回傳可供 QR 使用的實體 MAC。Windows 可枚舉本機 radio 並讀取其位址；QR 目標、實際 listener 與 SDP 服務必須一致，不能把全零位址放入 QR。
- 已確認：只有一個可用藍牙介面時直接使用；有多個時由 Windows 主控台選擇，邀請目標與服務綁定同一介面。無可用介面時清楚阻止啟動，不產生無效邀請。

文件依據（非實機互通證據）：

- [Android BluetoothDevice／createBond](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createBond())
- [Android 藍牙連線指南](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)
- [Android 藍牙權限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Windows SOCKADDR_BTH](https://learn.microsoft.com/en-us/windows/win32/api/ws2bth/ns-ws2bth-sockaddr_bth)
- [Windows BluetoothGetRadioInfo](https://learn.microsoft.com/en-us/windows/win32/api/bluetoothapis/nf-bluetoothapis-bluetoothgetradioinfo)

## 工程待驗證

以下不是已完成的能力，不得以文件或 API 存在當作實機成功：

- 持久授權的安全保存及重連驗證；同一手機識別、追加通道授權、原電腦驗證及撤銷。
- 配對期間 App 生命週期及系統配對結果續接。
- 藍牙服務、本機位址取得，以及以 QR 內位址發起安全系統配對的技術可行性。
- 唯一掃碼入口的終端相容性實機驗收。
- 授權協定的原子消耗、並行競爭、重試與成功回覆遺失處理。

## 已確認的驗收要求

- 在 SM-F7410 與 Windows 上，Wi-Fi 與藍牙模式各自顯示可掃描的標準 QR；手機不需手動輸入連線資料。
- 藍牙未系統配對時顯示系統配對流程，成功且邀請仍有效後續接應用授權；不能使用不安全 RFCOMM 降級。
- 期限由 Receiver 判定。邀請過期、已消耗、被更新、未知版本、格式錯誤或通道不符皆拒絕，不觸發貼上。
- 同一邀請遇到並行申請只能授權一支手機；錯誤申請不得消耗有效邀請。成功回覆遺失不允許第二支手機取得授權，且原手機有可恢復的處理路徑。
- 兩端重啟後，原手機可透過保存的授權及使用者「連線」操作恢復；不重用已消耗邀請。原藍牙工作階段的短暫斷線自動重連仍保留，不自動補送文字。
- Windows 撤銷原手機後，其既有連線及兩通道的授權均不可再觸發貼上。重新授權前必須掃新邀請。
- 同一手機追加另一通道須另外掃碼；不同手機不能冒充原手機追加權限。位址更新不得將既有授權導向未驗證的電腦。
- App 連線中不開放新掃描；中斷或切換不抹除待確認輸入的「輸入結果不明」，也不補送。
- 無相機權限、無藍牙權限、系統配對取消、邀請過期、終端尺寸不足及目標不可達等情境顯示可操作的說明，不假報「輸入就緒」。
- QR 及邀請／授權秘密不寫入一般日誌、逐字稿歷史或 QR 圖檔；主控台顯示仍應提示不要截圖分享或將輸出重新導向記錄檔。
- 語音辨識、貼上操作完成的定義、無自動 Enter、無自動補送、Windows 鎖定拒絕輸入等既有行為不變。

## 範圍邊界

本需求取代原家庭原型的手動連線介面及「QR 配對不在第一版範圍」限制；取代目前藍牙臨時碼加主控台 yes 的首次應用授權流程，不取消系統安全配對。

不新增多手機同時授權／輸入、多目標管理、自動跨通道切換、網路自動探索、外部相機入口、Node runtime、Wi-Fi TLS、Wi-Fi 背景保活或開機自動連線。

## 既有安全邊界

QR 顯示與短效邀請本身不提供傳輸加密。既有 Wi-Fi 使用未加密 ws://，仍限可信任家庭 LAN，不能因此宣稱適用公司或公共網路。藍牙仍需系統安全配對及獨立應用授權。

## 套件參考

- [Segno 官方文件](https://segno.readthedocs.io/en/stable/)：純 Python、無額外依賴，提供終端輸出。
- [python-qrcode](https://github.com/lincolnloop/python-qrcode)：另一個支援 console 輸出的 Python 套件。
- [qrcode-terminal](https://github.com/gtanner/qrcode-terminal)：原 issue 的終端顯示參考，不列為必用依賴。
