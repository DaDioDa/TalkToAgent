# Windows Receiver：從零開始的手動貼上原型

本切片只支援手機手動送出一段定稿文字；沒有語音收音、Gemini 連線、文字佇列或自動重送。Receiver 每個 WebSocket 連線最多接受一段文字，成功貼上後即關閉連線。

## 安全限制

- 手機和 Windows 電腦必須位於彼此可連線的可信任私人家庭區網。
- 手機使用 `ws://`，傳輸**沒有加密**。配對碼只用來授權連線，不會加密配對碼或文字；同一網路上的其他人可能竊聽。不要在辦公室、公共 Wi-Fi 或傳送敏感文字時使用。
- Receiver 每次啟動都在記憶體產生新的隨機配對碼，只顯示於正在執行的 Receiver 主控台，不寫入檔案或一般日誌。不要將 Receiver 主控台輸出重新導向到記錄檔，也不要分享配對碼。
- 文字最多 4 KiB UTF-8；WebSocket frame 上限為 32 KiB。空白、格式錯誤、無效 Unicode 或超限文字不會觸發貼上。
- Receiver 將文字寫入 Windows 剪貼簿，再對當下焦點送出 Ctrl+V；剪貼簿會被覆蓋、不會還原，也不會額外送空格或換行。回覆只表示剪貼簿和按鍵動作已完成，無法證明目標程式實際顯示文字。
- 連線結果不明時先檢查目標欄位，不要立刻手動重送；App 和 Receiver 都不會自動重試或保存文字。

## 安裝與啟動

1. 在 Windows 安裝 Python 3.10 或更新版本，開啟 PowerShell，確認：

   ```powershell
   py -3 --version
   ```

2. 從專案根目錄建立虛擬環境並安裝 Receiver 唯一的第三方相依套件：

   ```powershell
   cd windows_receiver
   py -3 -m venv .venv
   .\.venv\Scripts\python.exe -m pip install -r requirements.txt
   ```

3. 啟動預設埠 8765 的 Receiver：

   ```powershell
   .\.venv\Scripts\python.exe receiver.py
   ```

   主控台會顯示可供手機使用的 `ws://<Windows-IP>:8765/ws` 位址、隨機配對碼與操作提示。若 Windows 防火牆詢問，僅在可信任的私人網路允許 Python 接受連線。要使用其他埠可執行 `receiver.py --port 8766`，並在手機輸入相同埠號。

保持這個主控台開啟；按 Ctrl+C 停止 Receiver。每次重新啟動都會產生不同配對碼。

## 自動測試

在 `windows_receiver` 目錄執行 Receiver 的 WebSocket／驗證／假貼上測試：

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s . -p "test_*.py" -v
```

在專案根目錄執行 Android 協定單元測試：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

## 建置與手機到 Receiver 示範

1. 在專案根目錄建置 Android App：

   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```

   將 `app\build\outputs\apk\debug\app-debug.apk` 安裝到 Android 手機（已連接裝置時可用 `android install --apks=app\build\outputs\apk\debug\app-debug.apk`），開啟 **TalkToAgent 手動傳送**。

2. 手機與電腦連上同一個可信任家庭 Wi-Fi。在 App 輸入 Receiver 主控台顯示的 IP、埠 `8765` 和當次配對碼，按 **驗證 Windows Receiver**。畫面應顯示 Receiver 已驗證；同時明確顯示「Gemini 尚未設定，語音輸入仍不就緒」。配對錯誤時會顯示驗證失敗，且不會貼上。

3. 在 Windows 開啟記事本並將游標放在空白文件；回到手機，保留預填的 `TalkToAgent 手動測試` 或輸入一段測試文字，按 **傳送並貼上一次**。

4. 文字應只貼入記事本游標位置一次，內容前後沒有程式額外加入的空格或換行。Receiver 替換系統剪貼簿；可在記事本另一處按 Ctrl+V 驗證剪貼簿保留同一段文字。每次再次傳送前都要重新驗證 Receiver。

若未收到確認，先看記事本和剪貼簿，再決定是否手動重新驗證與傳送；確認遺失可能代表文字已貼上，重送可能造成重複。

## 共用 WebSocket 協定

- 端點：`ws://<IP>:<port>/ws`；Receiver 預設埠 8765。
- 每個連線第一個文字 frame 必須是 `{"type":"authenticate","pairingCode":"…"}`。正確配對碼回覆 `{"type":"authenticated"}`；錯誤配對碼回覆 `{"type":"authentication_failed"}` 並關閉連線。
- 驗證後只接受一個格式完全符合 `{"type":"final_text","text":"…"}` 的 frame。完成剪貼簿與 Ctrl+V 動作後回覆 `{"type":"pasted"}`；無效請求回覆 `{"type":"error","code":"invalid_message"}`，貼上動作失敗回覆 `{"type":"error","code":"paste_failed"}`。接著關閉連線。

此 Receiver 不保存或記錄定稿文字。未設定 Gemini 的手動示範不代表語音輸入已可用，也不會顯示整體「輸入就緒」。

## 藍牙直連（SM-F7410／Windows 實機試用）

先在兩端系統藍牙設定中開啟藍牙並完成配對。Windows 接收端從同一個入口啟動：

```powershell
cd windows_receiver
python receiver.py --bluetooth
```

手機選「藍牙」、列出並選取已配對的 Windows 電腦，輸入**主控台當次顯示的配對碼**，再按「驗證 Windows Receiver」。首次連線時，回到 Windows 主控台確認顯示的是自己的手機位址，輸入 `yes` 明確授權。先將游標放在 Windows 目標文字欄位，再在手機前景按住說話、放開後等候轉錄定稿與貼上；不會自動按 Enter。Windows 藍牙未開時啟動可能得到 WinError 10050；請先檢查系統藍牙開關，不要關閉安全選項或改走公司區網。

`python receiver.py --bluetooth` attempts a guarded RFCOMM startup. It sets
mandatory Windows link authentication/encryption socket options before binding;
option errors abort startup. After binding to an assigned channel and listening,
it registers an SDP record for UUID `9c8f8513-7d4d-4a70-82c7-11e1da28a041`
via Windows `WSASetServiceW` (`NS_BTH`, `RNRSERVICE_REGISTER`). Registration
failure closes the socket without accepting clients. On exit, it removes that
record with `RNRSERVICE_DELETE`; removal errors are surfaced. The default
Wi-Fi/WebSocket mode is unchanged. There is no persistent credential: the
ephemeral console-generated code expires when the process stops (Ctrl+C).

On first successful Bluetooth code authentication, the receiver asks at its interactive
console to authorize the displayed peer address. Type `yes` to pin that one
phone; any other address is refused even with the current code. The address
only (never the code or transcript) is stored atomically in the current user's
`%LOCALAPPDATA%\TalkToAgent\bluetooth-device.json`. Without a readable store
or interactive first-time consent, authorization fails closed. To revoke it,
stop the receiver and run `python receiver.py --forget-bluetooth-device`;
the next connection requires fresh console consent. Bluetooth sessions retain
an initial 10-second authentication deadline but have no 60-second post-auth
socket idle timeout. Connections are handled sequentially; 30-minute lock-screen
survival, disconnect/reconnect and Windows-locked rejection still need separate
real-device acceptance tests.

Run the isolated tests with `cd windows_receiver; python -m unittest test_bluetooth -v`.
Unit tests with an injected Winsock function verify the bound channel, SDP
registration/removal and fail-closed listener behavior; other tests exercise
framed authentication before text, per-ID ACKs, duplicate rejection and
desktop-lock checks. On the paired SM-F7410 and this Windows host, real RFCOMM
binding, SDP registration/removal, authorization, and a user-confirmed voice-to-paste
operation have been exercised. That single successful operation does **not** prove
30-minute background operation, lock handling, revocation or other devices work.

The SDP implementation follows Microsoft documentation for
[Bluetooth service set values](https://learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-wsaqueryset-for-set-service),
[Bluetooth registration and deletion](https://learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-wsasetservice),
[WSAQUERYSETW](https://learn.microsoft.com/en-us/windows/win32/api/winsock2/ns-winsock2-wsaquerysetw),
[CSADDR_INFO](https://learn.microsoft.com/en-us/windows/win32/api/nspapi/ns-nspapi-csaddr_info),
[SOCKADDR_BTH](https://learn.microsoft.com/en-us/windows/win32/api/ws2bth/ns-ws2bth-sockaddr_bth)
and [WSASetServiceW](https://learn.microsoft.com/en-us/windows/win32/api/winsock2/nf-winsock2-wsasetservicew).


本機排錯紀錄：Windows 藍牙關閉時，即使裝置管理員顯示介面正常，RFCOMM `bind` 仍會得到 WinError 10050。Windows RFCOMM 伺服器必須以 `BT_PORT_ANY`（`0xffffffff`）而非 `0` 要求通道；`0` 是用戶端用途。`SOCKADDR_BTH` 必須採 Windows 30-byte packed 配置，否則 `WSASetServiceW` 會得到 10022。這些問題已在本機修正並分別驗證 SDP 註冊與移除；實機文字貼上由使用者確認。
