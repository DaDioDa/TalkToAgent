# QR 邀請與授權協定 v1

實作依據：#9、#10。使用者已確認 P-256 簽章、Android Keystore／Windows DPAPI、隨機 challenge 與先保存授權再回覆的方案。此協定不加密 ws://，不承諾抵禦主動中間人。

## URI

ASCII 上限 1024 bytes。唯一格式為 `talktoagent://invite/1`，query 順序不影響解析。拒絕 percent escape、fragment、空白、重複／未知／缺漏欄位。二進位皆 canonical base64url 無 padding。

Wi-Fi：`?c=wifi&h=192.168.1.20&p=8765&i=<16-byte id>&k=<32-byte secret>&r=<32-byte fingerprint>`。
藍牙：`?c=bt&a=AABBCCDDEEFF&i=<id>&k=<secret>&r=<fingerprint>`。

IPv4 為 canonical dotted decimal，不允許前導零、0.0.0.0、multicast、broadcast；允許 loopback 供測試。port 為 canonical 十進位 1..65535。MAC 為 12 個大寫 hex，不允許全零或全 F。UUID 固定為 `9c8f8513-7d4d-4a70-82c7-11e1da28a041`。
Fingerprint 是 Receiver SPKI DER 公鑰的 SHA-256。
Target 固定 `wifi:<IPv4>:<port>` 或 `bt:<12 uppercase hex>`。
Receiver 以 monotonic clock 判定 600 秒期限（now >= deadline 拒絕），重啟不恢复未使用邀請。重顯不刷新，new 立即使舊邀請失效。

## 身分與編碼

P-256 (secp256r1)，SHA256withECDSA，DER 簽章，SPKI DER 公鑰。拒絕其他曲線或非 canonical 公鑰。私鑰不在網路交換。

`pack(values...)`：每個字串依次編碼為 u32 big-endian UTF-8 byte 長度與該 UTF-8 bytes。所有簽章及 HMAC 都用這個編碼，不對 JSON 字串簽章。epoch 為 0..2147483647 整數，簽章編碼時用 canonical 十進位。nonce 各 32 隨機 bytes。

## 握手

WebSocket /ws 文字 JSON；RFCOMM 4 bytes big-endian 長度＋UTF-8 JSON。frame 限 32768 bytes，拒絕 duplicate keys、unknown keys、錯誤型別及越序訊息。

1. 手機送 hello，欄位恰為 `v,type,mode,channel,target,invite,phoneKey,clientNonce`。v=1，type=hello，mode=invite|resume，channel=wifi|bt；invite 模式的 invite 是 QR id，resume 為空字串。phoneKey 是 SPKI，clientNonce 為隨機32 bytes。target 必須匹配 Receiver 啟動目標。
2. Receiver 回 challenge，恰為 `v,type,receiverKey,serverNonce,epoch,signature`，type=challenge。
3. `T = ["1",mode,channel,target,invite,phoneKey,clientNonce,receiverKey,serverNonce,str(epoch)]`。
   challenge.signature = ECDSA(pack("receiver-challenge", *T))。
   Android 先核對 QR fingerprint／既有 Receiver pin，後驗簽。未驗證不得送手機 proof 或改變 active 目標。
4. 手機送 proof，恰為 `v,type,signature,inviteProof`，type=proof。
   signature = ECDSA(pack("phone-proof", *T))。
   inviteProof = HMAC-SHA256(QR secret, pack("invite-proof", *T))；resume 固定空字串。
5. Receiver 驗 proof 並在共用鎖內重查 invitation/epoch/手機/通道與期限，授權成功先原子 commit 再回 authorized，恰為 `v,type,epoch,channels,target,signature`，type=authorized。
   channels 是已授權通道的排序字串，以逗號分隔（"bt"、"wifi"、"bt,wifi"）。
   signature = ECDSA(pack("receiver-authorized", *T, str(epoch), channels, target))。
   Android 驗簽並同步保存 active 目標成功後才回報 authenticated。epoch 必須與 challenge 一致。
6. 拒絕回 `{"v":1,"type":"error","code":"..."}`，關閉。code 不含文字、秘密或例外詳細資訊。

challenge 只供當前連線，10 秒內使用一次。邀請秘密不直接傳到 Receiver，只有 HMAC。已有手機時不同公鑰無法追加通道；追加通道及更新 target 需新有效邀請。resume 必須有該手機、通道及 target 的既有授權。Receiver id/key 是跨啟動固定身分。

## 持久化、恢复與撤銷

Android 先用不匯出的 Keystore 身分私鑰，同步加密保存 pending 邀請目標與 pin（不保存 QR secret），成功才送申請；active 目標在驗證成功回覆後保存。兩者分離，失敗邀請不得覆蓋 active 目標。
Receiver 用 SQLite FULL synchronous 交易保存單手機公鑰、通道目標與 epoch，Receiver 私鑰用 user-scoped DPAPI。單程序 OS lock 避免第二 Receiver 繞过在記憶體的邀請／撤銷狀態。儲存不可讀不重建成未授權。

成功回覆遺失時 Android 可對 pending 目標以原身分 resume；若 Receiver 已 commit 就恢復，否則拒絕並提示重掃。不自動補送文字。邀請一旦被授權成功消耗，不能被任何手機再次作 invite 使用；原手機恢复使用 resume。

撤銷和貼上開始在同一共用鎖內排序：revoke 增加 epoch、清除手機／兩通道／邀請，commit 後關閉現有 sockets。撤銷失敗仍使本程序 fail closed，不能聲稱持久成功。授權會話持有 epoch，每筆輸入在鎖內重查並核對桌面可輸入，再開始貼上；revoke 生效後不能開始新貼上。已開始操作可能完成，不回滾。
舊 MAC pin 不轉換成授權，升級要求重新掃碼。

## 轉錄定稿

兩通道統一 `{"v":1,"type":"final_text","id":"<ascii 1..128>","text":"..."}`，UTF-8 text 1..4096 bytes、非純空白、無 NUL，UTF-8 strict。回覆 `{"v":1,"type":"pasted","id":"..."}` 或 `{"v":1,"type":"error","id":"...","code":"..."}`。同會話 id 不得重複貼上。Wi-Fi 每連線只允許一次；藍牙保持工作階段。無自動 Enter／補送。「貼上操作完成」不等於目標接受或執行。

## 測試與驗收

沿用真實本機 WebSocket／可控 RFCOMM 協定入口＋記錄貼上邊界；Android 掃描結果到授權／連線行為。共用 URI 和密碼學向量作跨平台契約回歸。實際終端掃碼、未配對 SM-F7410 系統確認／續接、重啟及撤銷需另記實機證據，未驗證不宣稱完成。
