# Windows Receiver

在互動式 Windows 終端執行（不可重導輸出或保存終端記錄）：

```powershell
.\windows_receiver\.venv\Scripts\python.exe -m pip install -r .\windows_receiver\requirements.txt
.\windows_receiver\.venv\Scripts\python.exe .\windows_receiver\receiver.py
# 或：安全 Bluetooth Classic RFCOMM
.\windows_receiver\.venv\Scripts\python.exe .\windows_receiver\receiver.py --bluetooth
```

Wi-Fi 選定一個 LAN IPv4；藍牙選定一個實體 radio。單一候選直接使用，多個候選需主控台選擇，沒有有效候選則停止。QR、listener 與 SDP 使用相同 radio；安全選項或 SDP 失敗不降級。RFCOMM 系統藍牙配對不等於應用授權。

QR 是短效敏感邀請，持有者可能搶先授權；不要分享、截圖、保存或記錄輸出。只提供終端 QR，沒有 URI 手動輸入或圖片備援。非 TTY 拒絕顯示；終端尺寸不足時請最大化視窗或縮小字型並重新啟動。已啟動時調整尺寸後可重顯：

- `show`：重顯目前邀請，不延長期限。
- `new`：取代目前邀請，舊邀請立即失效；期限 10 分鐘，成功授權單次消耗。
- `revoke`：持久撤銷手機的 **Wi-Fi 與藍牙全部應用授權**，並關閉現有連線；不撤銷系統配對。可立即用 `new` 授權另一手機。
- Ctrl+C：停止，不撤銷持久應用授權。

安全授權資料位於 `%LOCALAPPDATA%\TalkToAgent\receiver`，私鑰使用目前 Windows 使用者 DPAPI，SQLite FULL synchronous 交易保存應用授權。只允許一個 Receiver 程序；存儲不可讀時不會重建為未授權狀態。重啟不保留未使用邀請，手機需主動 resume。成功回覆遺失後可 resume，不自動補送文字。

舊 `--forget-bluetooth-device` 保留作相容入口，但**現在會跨通道撤銷全部應用授權並退出**。先停止執行中的 Receiver（程序鎖會拒絕第二程序），或改用執行中主控台的 `revoke`。舊 MAC pin 不會遷移成應用授權，升級後須重新掃碼。撤銷落盤失敗會關閉會話並使本程序拒絕輸入，但不能聲稱持久撤銷成功；請停止並修復存儲，不要刪除資料來假裝完成撤銷。

Wi-Fi `ws://` 未加密，只用於可信任私人家用 LAN；QR／簽章不是 TLS，也不承諾抵禦主動中間人。每筆貼上重查應用授權及桌面可輸入狀態。撤銷生效後不開始新貼上，但已開始操作可能完成。剪貼簿會被替換，不自動 Enter；貼上操作完成不代表目標程式接受或執行。

自動測試使用真實本機 WebSocket、可控 framed RFCOMM seam 及記錄貼上替身，不代表實際 radio／SDP、未配對手機續接或終端掃碼驗收。
