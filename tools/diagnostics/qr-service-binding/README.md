# QR 返回 / 藍牙服務綁定回歸（僅診斷）

此目錄只由 `isolation.init.gradle` 額外載入，不加入正常 debug 或 release。測試安裝包固定為 `com.example.talktoagent.qrdiagnosis`；腳本先以 aapt2 檢查隔離，再安裝。禁止對使用者的 `.bluetoothdebug` 執行 clear、uninstall 或 connectedAndroidTest。

## 原因與修正

真實 MainActivity 在掃描頁覆蓋時 onStop 解除綁定；掃描返回後 onStart 非同步綁定尚未完成，onActivityResult 的解析/選擇/連線流程已抵達 prepareBluetooth。原本 null service 立即回報「背景服務尚未就緒；請稍後按連線」，終止明確的使用者操作。

修正只在記憶體保留當前 coordinator 與 transportGeneration，顯示驗證中，最多等待 10 秒。綁定回呼僅在目前 Activity 可見、同一 generation、同一 coordinator、藍牙通道與明確連線仍有效時續接一次；重新檢查權限、通知、藍牙與 selected endpoint。舊 service coordinator 不得覆蓋此邀請，舊 observer 也不得更新已離開或不同 service 的 Activity。手動中斷、切換通道、真正離開、destroy、逾時及失敗取消續接。掃描返回的 result-before-start 可等待；權限 continuation 的必要停止保留，實際 system bond reconciliation 保持原流程。

不新增自動重連，不保存 QR、secret 或新的授權資料，不改變系統配對與應用授權。

## 測試邊界

真實掃描返回 Activity、Android stop/start/result、授權 worker、Invitation、ConnectionCoordinator 與 UI；只攔住 ServiceConnection 交付。原始失敗字串斷言保留。延伸測試交付真實 isolated service binder；package-private device preparation boundary 取代設備查詢/配對/transport 啟動，計數續接，不假造已授權 UI，亦不向 synthetic MAC 配對或傳文字。

涵蓋 delayed callback 恰好一次、duplicate callback、舊 service flow 不覆蓋 QR、already-bound immediate、disconnect、channel switch、superseding generation、onStop、null binding、timeout。timeout 直接執行真正排程的 Runnable，未實際等滿 10 秒。離開案例直接呼叫 production lifecycle 方法；原始 scanner-return 案例仍由 Android 真正驅動 lifecycle。

## 已執行證據

指令：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:/TalkToAgent/tools/diagnostics/qr-service-binding/repro.ps1
```

- 修正前（父代理既有兩次）：1.786s / 1.622s，`Tests run:1 Failures:1`，exact symptom `背景服務尚未就緒；請稍後按連線`。
- 初次修正保留原斷言：1.751s，`OK (1 test)`。
- 延伸測試第一次暴露 observer 在 bluetooth=null 時仍收到舊 service 通知的 NPE；加入 observed-service/可見性 guard。
- 延伸測試 GREEN：1.966s、2.004s；已安裝 tight-loop 重跑 1.846s、1.826s，均 `OK (1 test)`。
- 加入 superseding generation 斷言後：1.963s；限制離開取消僅影響藍牙、保留 Wi-Fi 原行為後最終版：2.018s，均 `OK (1 test)`。

正常產物恢復指令（設定 JAVA_HOME、ANDROID_HOME 如 repro.ps1）：

```powershell
./gradlew.bat -PbluetoothTestApp=true :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

`BUILD SUCCESSFUL`；JVM XML 合計 57 tests，lintDebug 成功。最終 `app/build/outputs/apk/debug/app-debug.apk` 經 aapt2 確認 `com.example.talktoagent.bluetoothdebug` / `1.0-bluetooth-debug`，未安裝到使用者 app。androidTest APK 仍是 isolated test artifact，不能當正常 app 使用。

## 父代理複驗與安裝

- 裝置鎖定時複跑觸發真正的離開取消，不能算掃碼續接測試通過；此測試須在手機解鎖、保持前景時執行，不應停用產品的鎖屏取消規則。
- 使用者解鎖後，已安裝的同一回歸測試連續通過：1.878s、1.881s，皆 `OK (1 test)`；JVM XML 再核對為 57 tests / 0 failures / 0 errors。
- 使用者明確同意驗證後安裝；aapt2 再確認正常 `.bluetoothdebug` 套件後，以 `adb install -r` 更新成功，`MainActivity` cold launch 回傳 `Status: ok`。沒有 clear data、uninstall 或覆蓋正式套件。

## 後續實機授權確認

修復安裝後，使用者重掃曾收到另一個錯誤「應用授權被拒」。臨時探針僅輸出流程階段與 Receiver 記憶體中比對的布林結果，不輸出 QR、金鑰、地址或身分值：兩次皆是 invite / bt、目標相符、邀請有效，但 Receiver 已綁定身分與目前 App 不同，因此回覆 unauthorized。這是既有單手機授權保護，不是服務綁定修正失效；沒有放寬驗證。

使用者明確選擇改授權目前版本，親自在 Receiver 執行 revoke、new 後掃描。Receiver 檢查 accepted，手機實際事件為 proof_ready → authorized；安全回饋腳本回傳 PASS，使用者同時確認「已授權電腦」。本次因此已確認實機 RFCOMM 應用授權，但尚未驗證語音／貼上。無法從此證据確定舊授權屬於哪個 App 安裝或手機；不可武斷指認。

臨時 Android probe 已從 BluetoothFinalTextTransport 移除，沒有憑證儲存或協定變更。使用者同意清理後，已建置並安裝無 probe 的 `.bluetoothdebug`、啟動一般 Bluetooth Receiver，移除臨時 Receiver probe／捕捉腳本及診斷 JSONL；`app/src` 與 `tools` 搜尋無殘留診斷標籤。保留此隔離回歸工具，不加入正常 APK。重綁授權是使用者明確操作，不是自動刪除／重設資料。
