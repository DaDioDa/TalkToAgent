# Ticket 03 實作報告

狀態：**implementation-complete（human-validation-pending）**。

## 模型、分支與基準

第一步 PowerShell 實際輸出：

```text
PI_MODEL=gpt-6.1-sol
PI_PROVIDER=openai-codex
PI_REASONING_LEVEL=medium
```

符合指定 `openai-codex/gpt-6.1-sol / medium`。第一次呼叫受外層 shell 展開 `$env` 影響失敗；改用單引號後成功，未變更模型。

- 隔離 worktree：`C:/Users/TAC049~1.HSI/AppData/Local/Temp/pi-agent-c85d88b8-2882-44d-a98e3b94`。
- 分支：`feat/stitch-ticket-03-secondary`（原為 detached HEAD，本票建立分支）。
- Base：`be3ca697e442fa1ed6cc14799b08a546f9e23ce7`；`git merge-base --is-ancestor be3ca69 HEAD` 通過。
- 本票 commit：以 `git log -1 -- .scratch/stitch-ui/reports/ticket-03.md` 取得，避免自引用 SHA。
- 未修改主目錄、未合併其他 worktree、未 push。

已完整讀 implement、TDD（含 tests／mocking）、code-review、commit 技能；讀本地票／索引、spec-stitch-ui、CONTEXT、01 報告及三份設計素材。涉及授權的 ADR-0001／0002 已讀；不變更協定。未複製或提交未追蹤的原 Minimalist／QR 規格。

## 實作與所有權

- `NativeCScreen.showPanel`：所有次頁使用首頁同源深藍表面、分層圓角卡片、字級、薄荷綠操作與青色通道。固定返回／裝置分頁，資訊可捲動，沿用 560dp 寬度上限與 system bars／cutout／IME insets。
- `NativeCScreen.update`：更新真實階段、回饋、阻塞原因、電腦、通道及手動可用性，不重建金鑰／診斷輸入。語音忙碌 snapshot 回到首頁並停用次頁。
- 連線明示「連線邀請／應用授權／系統藍牙配對」界線，展示目前通道與連線不等於輸入就緒；QR、授權恢復、明確通道、disconnect 均沿用原 Event 與正式 MainActivity handler。
- 金鑰遮蔽、不回填、不保存到 view state；儲存及清除都清掉輸入欄，再交原安全儲存流程。診斷維持獨立單次手動測試、不冒充語音定稿。
- 最近定稿提供全文、空態與完整 COPY payload，不傳送；不再把當前手動操作的 phase／operationStatus 附在舊定稿旁。
- `MainActivity` **僅**改 `REQUEST_PERMISSION` 的已授權分支，提供「目前沒有收音、需主動開始」回饋。原權限請求、拒絕、永久拒絕設定入口、回來 refresh 均保留。
- `InvitationScanActivity` 自有標題、說明、狀態、取消使用固定深色 theme、卡片、捲動、安全區及 560dp 寬度上限。相機 decoder、Invitation.parse、單次結果、前景／pause、防重複、取消與權限拒絕行為未改；`FLAG_SECURE` 保留。
- 父方追加核准：首頁未選中的裝置分頁改 MUTED，兩側 `selected` 狀態正確；設定改原創本地 sliders 向量，48dp hit area、contentDescription「設定」。測試改語意 matcher，未刪除覆蓋。

**02 整合邊界：** NativeCScreen 新增次頁欄位／update 與 showPanel；render 僅改設定按鈕與 tabs 段，沒有改 transcript／waveform／preview／timer。MainActivity 僅 REQUEST_PERMISSION 分支。NativeCScreenTest／NativeCProductionTest 有設定 matcher 及次頁 scrollTo 更新，整合時亦留意測試檔衝突。NativeCStyle、GeminiLiveTranscriber、授權儲存、傳輸與 Receiver 沒有改動。

## TDD、回歸與診斷

測試 seam：預先授權的 `NativeCScreen.State/Event`，及正式 MainActivity／InvitationScanActivity 的原生 UI 與結果。沒有另造產品狀態機或 fake 正式入口。

1. 先寫「真實通道／連線與刷新回饋」tracer test，建置保存 baseline APK；5556 交付後，baseline 因缺少「目前通道：Wi-Fi」RED，再以新版 GREEN。
2. QR native title／cancel／安全旗標測試以保存 baseline APK 確認 RED、新版 GREEN。這個 RED 是樣式已實作後對 baseline 執行的 negative control，不宣稱整個 QR 切片嚴格先跑 RED。
3. 新增草稿／回饋、遮蔽／清除、手動停用／忙碌入口、完整 COPY／不附手動結果、tabs 可及性、真鍵盤、正式金鑰保存／不回填／清除、已允許權限／resume 不收音與無相機取消測試。其餘為既有功能回歸，不宣稱每項都有產品 RED。
4. 強制顯示軟鍵盤後，測試出現 submit 91% 可見。單檔可重現，等待 IME 幾何穩定仍 RED；幾何探針確認 ScrollView.scrollY=0，原因是 Espresso scrollTo 在已 90% 可見時跳過操作。改用真原生 swipe，submit 100% 可見、IME 持續顯示、返回可達，GREEN；診斷探針已移除，未為測試假改產品。
5. 一次安裝遇到 `device offline`，沒有誤認新版已安裝；重新安裝成功才執行 GREEN。
6. 外部 `pm revoke` 必須在 runner 啟動前執行，否則 Android 殺掉 target／instrumentation。無相機測試改條件式 assumption，並以獨立 denied invocation 實際通過。
7. 寬視窗測試後 Launcher 保留 tablet taskbar，導致既有小 frame 的 scrollTo 再遇 90% 門檻；恢復 phone config 並重啟 5556 的 Launcher，沒有改 01 產品／測試以掩蓋環境。官方 CLI layout server 佔用 UiAutomation 時，先停止 `com.android.cli.interact.instrumentation` 才跑 runner；此環境失敗不列成功。

## 環境與最終命令／結果

- JDK：`JAVA_HOME=D:/andriodStudio/jbr`。
- SDK：`ANDROID_HOME`／`ANDROID_SDK_ROOT=C:/Users/Ta.Hsiung/AppData/Local/Android/Sdk`。
- 官方 CLI：`.../cmdline-tools/15859902/bin/android.exe`；已跑 `skills list`，完整讀 android-cli 與 references/interact.md，先跑 layout／screen help。
- 本票獨佔 `TalkToAgentStitchSecondaryApi36`／`emulator-5556`／API36 AOSP；所有 adb／CLI 裝置操作指定 serial。未操作 5554，未跑 connectedDebugAndroidTest。
- Phone：1170×2466px／density480＝390×822dp；fontScale1.0。
- Compact：960×1920px／density480＝320×640dp；fontScale2.0；另有 320×480dp 原生 frame／2.0 字體／50 行定稿測試。
- Wide：2400×3000px／density480＝800×1000dp，內容置中限制 560dp。
- 真鍵盤：`settings put secure show_ime_with_hard_keyboard 1`；測試 assert 真 IME visible 且動畫幾何穩定、操作後仍 visible。
- 三鍵與手勢透過 `cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton`／`gestural` 檢查。

PowerShell 設上述環境後：

```text
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am force-stop com.android.cli.interact.instrumentation
adb -s emulator-5556 shell am instrument -w com.example.talktoagent.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell pm revoke com.example.talktoagent android.permission.CAMERA
adb -s emulator-5556 shell am instrument -w -e class com.example.talktoagent.StitchSecondaryScreenTest#scannerWithoutCameraPermissionCancelsWithoutReturningAnInvitation com.example.talktoagent.test/androidx.test.runner.AndroidJUnitRunner
```

- **JVM：57 tests／0 failures。build＋AndroidTest APK＋lint：BUILD SUCCESSFUL**。Lint 不宣稱零 warning，報告 `app/build/reports/lint-results-debug.html`。
- 最終完整三鍵 runner：**OK (29 tests)**；其中無相機條件測試在 CAMERA 已授權時 assumption 略過，另以上述外部 revoke 後獨立 **OK (1 test)** 驗證，沒有把略過當通過。
- 同一產品版本手勢完整套件：**OK (27 tests)**（當時尚未追加正式金鑰／無相機兩項回歸）；compact／2.0 字體／真鍵盤手勢的本票 **8 tests 全通過**；wide 連線／QR **2 tests 全通過**。
- 完整 suite 保留 AuthorizationPersistenceTest、ConnectionLifecycleUiTest、NativeCProductionTest、NativeCScreenTest、StitchVoiceHomeTest、TraditionalVoiceDeliveryTest 與既有 JVM 期限／單次提交／晚回呼／授權測試。
- `git diff --check` 通過。本地 raw log 位於 `.scratch/stitch-ui/ticket03-*.txt`（PowerShell tee 編碼可能影響終端中文）；build／raw log 不提交。

## 原生圖像證據與差異

提交於 `.scratch/stitch-ui/screenshots/ticket-03/verified/`：normal 十張、compact-font2 九張、wide 兩張。尺寸與重複 index 已逐檔以 PNG metadata 篩選，不用 pull 後的檔案時間假推最新環境。已視覺檢視正常／小字窗大字體／真鍵盤、連線、設定、診斷、QR UI、全文／空態與寬視窗代表圖。

- `connection`／`permission-denied` 等 snapshot 是 instrumentation 的 State/Event 測試資料，不是假冒真 Windows 授權成功或系統拒絕。
- `permission-production-granted`、`latest-empty-production` 是正式 MainActivity；金鑰正式保存／清除也實際走安全儲存 handler。
- `qr-native-ui-no-camera` 是正式 Activity 的原生 view rendering，**明確暫時隱藏整個 camera view 後畫到 bitmap，沒有相機／QR 像素，沒有清掉 FLAG_SECURE**。不是相機畫面 screenshot，不宣稱證明實際光學辨識；其餘圖為真原生裝置 screenshot。
- 無次頁專屬畫稿，按首頁同源卡片與設計規範延伸；仍採離線 Android 字體，sliders 為原創幾何向量。沒有外部素材／CDN。
- 長內容與字體2.0 以捲動承接，返回／取消固定可到達；QR 說明長時取景區需向下捲動。系統狀態列／導覽／鍵盤維持系統畫面，鍵盤的淺色不是 App 動態改色。

## 本地雙軸 review

基準已解析，檢查 working diff：`git diff be3ca69 -- <本票 paths>`；提交後可重現 `git diff be3ca69...HEAD` 及 `git log be3ca69..HEAD --oneline`。本呼叫沒有 Agent／SubagentWorkflow 工具，**沒有獨立雙代理 review**；以下是本地檢查，父方將補指定 Sol6.1／medium 雙軸審查。

### Standards

依 repo AGENTS、Java Views／State-Event 契約、既有安全／授權 ADR 及 code-review Fowler baseline 檢查。未發現未解硬標準違反；不新增秘密、憑證、lockfile、傳輸變更或正式 debug 假資料。可能 Duplicated Code：首頁與連線頁各自設定兩個 tab 的圖示／色彩；範圍小且維持既有首頁幾何，未為此引入額外 UI 框架。此為判斷式 smell，不是硬違反。

### Spec

已修正次頁只換色、狀態不刷新、clear 留草稿、手動結果錯附舊定稿、未選中 tab 綠色與設定缺語意圖示的缺口。所有既有 Event 仍接正式 handler；MainActivity 的唯一改動不啟動 capture。QR decoder／parse／result 與安全旗標不改。未實作 02、帳號、手動 IP、永久碼、持久歷史、自動重送或系統配對替代 UI。未解的驗收項是以下 human-only／光學與外部環境範圍，不冒稱端到端已驗收。

## 待真人驗收

- 真實手機掃描 Windows 邀請：有效／無效／失效 QR、camera 系統拒絕／返回、實際 decoder 畫面與相機手感。
- 真 Windows 藍牙系統配對、各通道應用授權取得／恢復／撤銷、Wi-Fi 單次連線、disconnect 與手動文字貼上；原生事件／既有授權 tests 不是硬體互通證明。
- 麥克風 Android 系統 dialog 的人類允許／拒絕／永久拒絕設定修復；自動已授權 resume 不收音、拒絕 snapshot 及正式無 CAMERA 取消已覆蓋，但不代替完整人機 journey。
- 真 Gemini key／收音／Windows 全流程、升級既有安裝與授權資料、實際手機剪貼簿、TalkBack 人工朗讀與單手操作。原生完整 COPY payload／本機 Keystore 保存已驗證。

## 已授權 commit 提案（執行前記錄）

使用者總授權取代逐次詢問；僅 add 以下明確路徑，不 push、不繞 hooks。

```text
feat(android): 統一次頁深色導覽並保留安全操作回饋

次頁沿用首頁卡片與原生操作契約，讓狀態刷新保留草稿，區分連線、授權與定稿結果；補齊正式金鑰與原生回歸證據，保留外部實機待驗收界線。
```

Add 清單：
- `app/src/main/java/com/example/talktoagent/NativeCScreen.java`
- `app/src/main/java/com/example/talktoagent/MainActivity.java`
- `app/src/main/java/com/example/talktoagent/InvitationScanActivity.java`
- `app/src/main/res/drawable/native_c_settings.xml`
- `app/src/androidTest/java/com/example/talktoagent/NativeCScreenTest.java`
- `app/src/androidTest/java/com/example/talktoagent/NativeCProductionTest.java`
- `app/src/androidTest/java/com/example/talktoagent/StitchSecondaryScreenTest.java`
- `.scratch/stitch-ui/issues/03-secondary-screens.md`
- `.scratch/stitch-ui/reports/ticket-03.md`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/normal/ticket03-connection.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/normal/ticket03-diagnostics-draft.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/normal/ticket03-key-masked.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/normal/ticket03-key-real-keyboard.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/normal/ticket03-latest-long-320dp-font2.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/normal/ticket03-latest-empty-production.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/normal/ticket03-permission-denied.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/normal/ticket03-permission-production-granted.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/normal/ticket03-qr-native-ui-no-camera.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/normal/ticket03-settings.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/compact-font2/ticket03-connection.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/compact-font2/ticket03-diagnostics-draft.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/compact-font2/ticket03-key-masked.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/compact-font2/ticket03-key-real-keyboard.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/compact-font2/ticket03-latest-long-320dp-font2.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/compact-font2/ticket03-permission-denied.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/compact-font2/ticket03-permission-production-granted.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/compact-font2/ticket03-qr-native-ui-no-camera.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/compact-font2/ticket03-settings.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/wide/ticket03-connection.png`
- `.scratch/stitch-ui/screenshots/ticket-03/verified/wide/ticket03-qr-native-ui-no-camera.png`

排除：全部 Gradle／APK 產物、保存 baseline RED APK、raw log／layout JSON、raw／過期 screenshot、commit 訊息檔，以及所有不在上列的 scratch 檔案。未追蹤原規格與其他 worktree／02 檔案不 add。
