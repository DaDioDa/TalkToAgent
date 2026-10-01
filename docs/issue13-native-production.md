# Issue #13：原生 C 正式接線與驗證紀錄

## 範圍與前置

- #12 在 GitHub 仍 OPEN；其程式前置已由 `7b0c7cd` 與使用者接受的 `ec73fb3` 滿足。本次依使用者指示實作下一張，不修改／關閉任何議題、不繞改追蹤器依賴。
- 保留已接受的低飽和藍 Material 3、明確 XML widget styles／ColorStateList、固定大主區、全寬取消、獨立資訊捲動、深色與 system insets。舊議題灰階描述以此次明確視覺批准為準。
- 未提交／推送 Git，未另派實作代理，未修改既有 prototype 或使用者未追蹤規格。

## 正式架構

`MainActivity` 正式使用既有 `NativeCScreen`；不使用 debug preview 或模擬資料。刪除舊按住式首頁與常駐文字／金鑰輸入。State 維持不可變快照，新增真實操作說明及獨立診斷傳送可用性；panel 更新不抹除正在輸入的金鑰／診斷文字。

`VoiceUtterance` 是 Activity 與 JVM 測試共用的單一正式協調 seam：

- start → PREPARING；真實 AudioRecord capture callback 才 RECORDING。
- 可控單調時間；45 秒從 start 起算（含準備），40 秒預警一次，manual／deadline 共用 finish。
- 初始化 10 秒、定稿等待 8 秒；定稿截止先讓既有 FinalTranscript 做安靜期最後檢查。
- session token 防止取消／失敗／新段後的舊辨識與結果回呼復活流程。
- 只有有效、完成繁體轉換並再次驗證的定稿進入 SENDING；診斷文字同樣只走一次提交，但不偽造收音／震動，也不替換最近口述定稿。
- leave 取消尚未送出內容；SENDING 改 UNKNOWN，可信完成結果保留。僅結果 enum metadata 可保存於 Bundle，沒有文字／key／授權或可重試交易。
- readiness 與結果分開；重新驗證不自行改寫 UNKNOWN、不補送。開始新段仍受 InputReadiness 與 pending／交易限制。

Activity adapters：Gemini 收音／收尾、短震／雙震、錄音期間亮屏、onPause／onStop 及 SCREEN_OFF 取消、QR scanner、既有授權恢復、通道選擇、主動斷線、安全金鑰、麥克風權限、最近定稿複製與診斷。Wi-Fi 保留每連線一次傳送；藍牙保留工作階段，增加結構化結果供協調 seam 使用，移除既有斷線後自動重連，以符合 #13 的使用者主動恢復規則。未修改 Receiver／授權／傳輸訊息格式或資料 schema。

本機每段最多一次 submission **不是**目標程式 exactly-once 保證。「貼上操作完成」只代表 Receiver 的剪貼簿／貼上操作。

## 測試先行證據

Build 目錄保留實作過程 logs（非需提交的交付文件）：

- `issue13-red.log`：第一個準備／45 秒／預警測試，16 個缺少協調 interface 的 compile errors。
- `issue13-manual-red.log`：診斷不得偽裝 capture，缺少 submitManual 的 compile errors。
- `issue13-timeouts-red.log`：3 tests / 1 failure（初始化期限未實作）。
- `issue13-restore-red.log`：缺少 result-only restore interface。
- `issue13-refusal-red.log`：缺少提交前本機拒絕處理。
- `issue13-late-mic-red.log`：晚麥克風啟動在 timer ticks 之間越過初始化期限的 failing assertion。
- 各 slice 最小實作後及最後完整 JVM suite 都 green；另補既有已實作安全行為的回歸排列，沒有平行測試狀態機。

## 自動驗證：本次實際結果

Windows PowerShell，JDK／SDK：

```powershell
$env:JAVA_HOME = 'C:/Users/Ta.Hsiung/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2'
$env:ANDROID_HOME = 'C:/Users/Ta.Hsiung/AppData/Local/Android/Sdk'
./gradlew.bat -PbluetoothTestApp=true testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease
./gradlew.bat -PbluetoothTestApp=true lintDebug
python -m unittest discover -s windows_receiver -p 'test_*.py'
python -m unittest discover -s tests -v
```

| 驗證 | 本次結果 |
|---|---|
| Android JVM 全套 | **57 tests / 0 failures / 0 errors**（原 46 + 正式協調 11） |
| Debug、AndroidTest、Release | **BUILD SUCCESSFUL** |
| lintDebug（獨立執行） | **0 errors / 16 warnings**；BUILD SUCCESSFUL |
| Receiver 無真實貼上測試 | **36 tests，OK**；使用 recording paste boundary |
| JCA／Python 授權互通 | **2 tests，OK**；暫存授權與本機 WebSocket，非實機貼上 |
| git diff --check | 通過，只有 Git CRLF 提示 |
| aapt2 debug badging | `com.example.talktoagent.bluetoothdebug`，launcher `MainActivity` |
| aapt2 release manifest | 包含 `MainActivity`，不含 `NativeCPreviewActivity` |

JVM 協調 11 cases 包含：準備 inclusive clock／一次 5 秒預警、兩種 finish／deadline 順序與重點擊、本機提交前拒絕、初始化與定稿期限、初始化截止間隙的晚 mic callback、取消與新舊 session、三個未送出階段的 leave／lock 語意、已提交後 UNKNOWN／可信完成與新段、空／超限／轉換失敗、diagnostics 不偽造 capture、重建只恢復結果 metadata。既有 InputReadiness、FinalTranscript、繁體交付 instrumentation source 與連線回歸保留。

Logs：`app/build/issue13-build.log`、`issue13-lint.log`、`issue13-receiver.log`、`issue13-interop.log`；JUnit：`app/build/test-results/testDebugUnitTest/`；lint：`app/build/reports/lint-results-debug.xml`。

## 真機／整合：BLOCKED，不能計為通過

`adb devices -l` 本次只有標頭，沒有裝置。實際執行：

```powershell
./gradlew.bat -PbluetoothTestApp=true connectedDebugAndroidTest
```

結果：**BUILD FAILED — DeviceException: No connected devices!**，log `app/build/issue13-connected.log`。

- 新增／更新的真實 MainActivity integration tests（3 cases）已編譯：正式入口與重建、設定／診斷／最近定稿導覽、離開返回不自動錄音、通道／主動斷線 handler。**未執行**。
- 原有 NativeCScreen 11 cases 的先前成功不當作本次接線成功；本次全部 connected instrumentation **未執行**。
- 真實 START／FINISH 在準備／收音／定稿的 Activity 接線與 busy controls、SCREEN_OFF 真正鎖屏、錄音期間亮屏、原生輕震／雙震、無提示音：**未實測**。JVM 只證明協調語意，不能證明硬體。
- QR／既有與未完成授權續接、Wi-Fi one-send、藍牙持續 session、設定／權限修復、真實複製：程式已接線，**新入口 runtime 未驗證**。
- 手動與 45 秒到限的麥克風 → Gemini → Receiver → 專用目標貼上、取消後零意外貼上、unknown 不補送：**未測**，沒有授權測試裝置／Gemini credential／專用 Receiver target；未使用任意前景應用或其他儲存區金鑰。
- 左手人體握持、取消列左中右手感、三鍵／手勢導覽與放大字體的新入口真機觀察：**未測**。

本次僅產出 APK，**沒有安裝／啟動任何裝置 APK**；既有手機安裝狀態不變。沒有卸載、clear data、覆蓋正式套件或修改裝置全域設定。恢復裝置後只能使用隔離 bluetoothdebug 測試套件；遇簽章衝突須停止。

因此：程式與核心自動回歸已交付，但 #13 的真機／端到端驗收及實際 active-voice Activity 覆蓋尚未完成，不宣稱議題全部 acceptance criteria 通過。
