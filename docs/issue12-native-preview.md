# Issue #12：原生 C UI 開發預覽

本次只交付可重用的原生 Java 畫面與明確隔離的 debug 預覽；真實功能等待 #13。MainActivity 與 main manifest 未改動。沒有 Web runtime、收音狀態機、網路、儲存、剪貼簿、計時器或震動操作。不要在預覽輸入真實金鑰或操作真實授權。

## Windows PowerShell 執行

```powershell
$env:JAVA_HOME = 'C:/Users/Ta.Hsiung/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2'
$env:ANDROID_HOME = 'C:/Users/Ta.Hsiung/AppData/Local/Android/Sdk'
$adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
./gradlew.bat -PbluetoothTestApp=true assembleDebug assembleDebugAndroidTest
& $adb -s R5CX71D1SKD install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s R5CX71D1SKD shell am start -n com.example.talktoagent.bluetoothdebug/com.example.talktoagent.NativeCPreviewActivity
```

`bluetoothdebug` 是既有的安全並存 suffix，不是正式包。安裝若出現簽章衝突請停止，**不可 uninstall／清資料／覆蓋正式 app**。connected tests 會清理它管理的測試包，因此測試後需重新安裝預覽 APK。正式 launcher 仍是 MainActivity。`NativeCPreviewActivity` 僅存在於 debug source set／manifest，release APK 沒有此 Activity。

目前 `android` CLI 不在 PATH，使用既有 Gradle wrapper 與 SDK adb。

## 公開 presentation／action 契約

檔案：`app/src/main/java/com/example/talktoagent/NativeCScreen.java`。

```java
NativeCScreen screen = new NativeCScreen(activity, event -> {
    // 主程式決定如何處理 event.action / event.text；畫面不執行裝置操作。
});
activity.setContentView(screen);
screen.render(new NativeCScreen.State(
    NativeCScreen.Phase.UNKNOWN, false, "電腦 · 已連線", 5, "最近一段定稿",
    java.util.Collections.singletonList(new NativeCScreen.Blocker(
        "金鑰無效", NativeCScreen.Action.KEY))));
```

- 建構、render 與事件消費均在 UI thread。host 應固定 portrait；預覽已在 debug manifest 宣告。
- State 為不可變快照：phase、ready、computer（供 host 提供電腦與連線摘要）、remainingSeconds、latestFinalText、blockers。列表防禦性複製，blocker 只能指向 KEY／PERMISSION／CONNECTION 修復入口。
- `ready` 由 host 提供，**不由已連線文字或上一段成功推導**。結果不明仍保留警告；只有 host 的新快照可以改變階段。
- `render(State)` 顯示主畫面並預設隱藏定稿。上方資訊區可獨立捲動，電腦／就緒摘要單行，phase 最多兩行，期限與即將結束提示保留列高；三個次要入口水平排列、48dp 高。障礙提供第一項 48dp 修復入口（長文截斷），所有障礙／完整狀態在可捲動次頁展示。扣除固定底部空間後，上方資訊與大型主觸控面分配 45%／55%；主面左右留 16dp，上緣留 8dp，取消前留 16dp 空白。取消列全寬 56dp，不再使用深灰分隔線。system bars／cutout／IME insets 留在控制之外。小高度／放大字體時只捲資訊，不捲動主面或取消，也不因狀態長文挪動控制。
- 次頁導覽由同一畫面處理，並輸出 CONNECTION／SETTINGS／LATEST／DIAGNOSTICS／KEY／PERMISSION／HOME 事件。內部導覽先完成，再發布事件，因此 callback 同步呼叫 render 時 host 快照具有最後決定權，不會被舊次頁覆寫。次頁可捲動。render 新快照會回主畫面；host 可以在忙碌開始時直接 render。
- START／FINISH／CANCEL 只輸出意圖，不自己轉換 phase。取消左／中／右共用同一獨立 hit area。
- SCAN_QR、RECOVER_AUTHORIZATION、BLUETOOTH、WIFI、DISCONNECT 提供 QR／授權恢復／明確通道／主動中斷的位置，沒有舊 IP／配對碼欄位。
- COPY 帶 latestFinalText，MANUAL_SEND 帶手動輸入，SAVE_KEY 帶新輸入，CLEAR_KEY／REQUEST_PERMISSION 無 payload；其餘事件 text 為空。畫面不存取任何真實系統服務。金鑰不顯示已存值，不保存 view state，送出後清空欄位；預覽事件標籤不顯示金鑰 payload。
- 權限修復不自動開始；完成文案不宣稱目標程式接受文字；UNKNOWN 不重送、不增加強制確認對話框。

## 預覽檢查方法

頂端永久標示「開發預覽／模擬資料」，事件後也保留此聲明；通知固定兩行避免事件文字改變預覽高度。先選「展開開發控制」（預設收合），再選「切換模擬狀態」直接選十個 enum；選「切換模擬就緒／障礙」獨立改 ready；「選擇障礙原因」可選金鑰、權限、連線或多項長文；「期限 45／5 秒」直接選期限。沒有自動倒數或平行語音流程。操作事件在標籤可見，不代表功能完成。模擬 selectors 在忙碌時仍可用，屬於 debug host，不是正式次要入口。

## 十階段檢查結果

下表以 `NativeCScreenTest.allTenPhasesRespectReadinessAndLockSecondaryEntries` 真機自動 UI 測試為依據；每階段皆測 ready=true／false、取消及三個次要入口。不是正式語音流程驗收。

| phase | 主操作 | 取消 | 次要入口 | 結果 |
|---|---|---|---|---|
| UNAVAILABLE | 停用，修復原因 | 停用 | 可用 | 通過 |
| READY | 僅 ready 可開始 | 停用 | 可用 | 通過 |
| PREPARING | 停用，尚未收音 | 可用 | 停用 | 通過 |
| RECORDING | 可結束並送出 | 可用 | 停用 | 通過 |
| FINALIZING | 停用，等待定稿 | 停用 | 停用 | 通過 |
| SENDING | 停用，無法撤回 | 停用 | 停用 | 通過 |
| COMPLETED | 僅 ready 可開始 | 停用 | 可用 | 通過 |
| CANCELLED | 僅 ready 可開始 | 停用 | 可用 | 通過 |
| FAILED | 僅 ready 可開始 | 停用 | 可用 | 通過 |
| UNKNOWN | 僅 ready 可開始，警告仍在 | 停用 | 可用 | 通過 |

PREPARING／RECORDING 的 5 秒期限與即將結束提示已測；只驗證輸入快照文字，**不是 45 秒計時驗證**。連線修復、QR／授權／通道／中斷、金鑰／權限、定稿隱藏／展示／複製 payload、診斷手動文字 payload 均通過公開 seam 測試。

## Red → green 證據（2026-10-01）

1. 首先加入開始輸入測試，`compileDebugAndroidTestJavaWithJavac` 報 8 個找不到 NativeCScreen／NativeCPreviewActivity 的編譯錯誤；最小主畫面實作後單檔 connected test 通過。
2. 新增最近定稿／診斷導覽測試：2 tests / 1 failure，`NoMatchingViewException`（定稿未展示）；加入次頁及 payload 後通過。
3. 新增直接 phase 與 readiness 選擇測試：3 tests / 1 failure，`NoMatchingViewException`（缺少模擬狀態選擇）；加入 debug selectors 後通過。另一次裝置鎖屏造成 NoActivityResumedException；喚醒並 dismiss keyguard 後重跑取得真正缺少控制的 red。
4. 新增金鑰／權限操作測試：SAVE_KEY／CLEAR_KEY／REQUEST_PERMISSION 缺少造成 3 個編譯錯誤；加入 intent-only 控制後通過。
5. 既有 slice 上補回歸矩陣、400dp 小高度＋長文穩定矩形、取消左中右事件、當前 system navigation inset 檢查。沒有 mock 或私有欄位測試。

```powershell
./gradlew.bat -PbluetoothTestApp=true connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.talktoagent.NativeCScreenTest"
./gradlew.bat -PbluetoothTestApp=true testDebugUnitTest --tests com.example.talktoagent.InputReadinessTest
./gradlew.bat -PbluetoothTestApp=true assembleDebug assembleDebugAndroidTest assembleRelease
./gradlew.bat -PbluetoothTestApp=true lintDebug
```

- SM-F7410 / R5CX71D1SKD / Android 16：初次交付 **8 tests, 0 failures, 0 errors**；review 修正後單檔 **9 tests, 0 failures, 0 errors**，BUILD SUCCESSFUL。結果：`app/build/outputs/androidTest-results/connected/`，HTML：`app/build/reports/androidTests/connected/debug/index.html`。
- 既有 InputReadinessTest：**2 tests, 0 failures**。後續完整回歸：Android JVM **46 tests**、SM-F7410 instrumentation **15 tests**、Windows Receiver **36 tests**、授權互通 **2 tests**，全部 0 failures／errors。授權互通首次因 shell 未設 JAVA_HOME 無法啟動，設定同一 JDK 後重跑通過。
- Debug／AndroidTest／Release build 成功；獨立執行 lintDebug 成功（含現有及新增的 hardcoded text、程式式 View constructor 警告，沒有 lint error）。本模組使用帶 Actions 的程式式建構，沒有 XML inflate constructor。
- 將 lint 與 connected／assemble 合併執行曾遇既有 authorization-test-assets 缺少 generateDebugAndroidTestLintModel dependency 的 Gradle validation error；分開執行成功，未改動該既有 build 配置。
- aapt2 檢查 release manifest：沒有 NativeCPreviewActivity，launcher 仍 MainActivity。debug badging package 為 `com.example.talktoagent.bluetoothdebug`；adb install -r Success，實際 am start 成功。
- 真機目前 navigation_mode=2（手勢）；測試驗證全寬取消區在當前 system inset 上方、至少 48dp 高。只證明當前自動佈局條件，不是人體操作驗收。

## 首版 Review 修正：同步 render 與小高度核心狀態（歷史紀錄）

僅修正兩個已驗證問題，未變動真實功能或 MainActivity。

- 公開 UI 測試 `synchronousHostRenderWinsOverConnectionNavigation`：CONNECTION callback 同步 render(PREPARING)，最後必須顯示 busy 主畫面、主操作停用、沒有掃描 QR。修正前 `NoMatchingViewException`（準備狀態被次頁覆寫）；調整為先導覽再事件發布後通過。COPY／MANUAL_SEND payload 測試仍通過。
- 擴充 `compactHomeLongReasonsDoNotMoveControlsAndCancelEdgesOnlyEmitCancel`：400dp、兩個障礙含長原因，在 PREPARING／RECORDING 皆以 `isCompletelyDisplayed` 確認 phase／5 秒期限／即將結束提示不需捲動，主區與取消矩形穩定且至少 48dp。修正前核心狀態的完整可見性 assertion 失敗；移除 home ScrollView，保留固定資訊列並將次要入口改水平、長補充內容留次頁後通過。
- 本次先跑同一單檔取得 **9 tests / 2 failures** 的 red；實作後同一單檔 **9 tests / 0 failures / 0 errors** 的 green（SM-F7410，包含 Java／AndroidTest 編譯與 APK 打包）。證據暫存於 `app/build/issue12-review-red.log`、`app/build/issue12-review-green.log`。修正後已重跑完整 Android suite（46 JVM／15 instrumentation）、Release build、獨立 lintDebug，以及 Receiver 36 項與授權互通 2 項；全部通過。另重新檢查 release manifest 無預覽 Activity，並在手機重裝、啟動並存預覽版。

## Material 3 重設與對比修正（2026-10-01）

本輪採現有 Java Views／Material Components 1.10.0，沒有 Compose 遷移。`Theme.TalkToAgent.NativeC` 只套用於畫面的 ContextThemeWrapper 與 debug host；正式 theme、MainActivity、main manifest 不變。

- scoped `native_c_theme.xml`／night colors 集中低飽和藍與 surface 色彩。大型主面採 tonal、提交／複製／QR／權限請求採 filled、取消／返回／清除／中斷採 outlined、次要入口採 text；欄位為 M3 outlined TextInputLayout。次頁有標題／說明層級，沒有巢狀卡片。
- 使用者回報白字不可見後，實機截圖重現主面近白字／淺藍底及看不到的次要標籤。原因是以 widget style 當 theme overlay 建構按鈕，讓預設樣式覆寫元件變體。改為 XML 的明確 `style`，搭配 scoped ColorStateList：tonal 使用 primaryContainer/onPrimaryContainer，filled 使用 primary/onPrimary，次要文字使用 primary/surface；停用前景／底色使用 onSurface 的 38%／12%。文字與麥克風 icon 共用同一狀態色。
- 金鑰仍為 password、不保存 view state、不展示已存值；提交前清空欄位，再發布 payload，讓同步 host callback 保持最後決定權。預覽通知永遠保留模擬聲明，且遮蔽 SAVE_KEY payload。
- 小高度策略改為僅資訊區捲動，取代首版固定小字列；400dp 測試以 scrollTo 驗證資訊可完整讀取，同時保留主面／取消固定矩形、至少 48dp，以及取消左右邊緣只發 CANCEL。同一測試使用 1.3 倍字體和長原因。

### 本輪實際驗證

沿用上方 PowerShell 的 JDK／SDK 設定，分開執行：

```powershell
./gradlew.bat -PbluetoothTestApp=true assembleDebug assembleDebugAndroidTest assembleRelease testDebugUnitTest
./gradlew.bat -PbluetoothTestApp=true connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.talktoagent.NativeCScreenTest"
./gradlew.bat -PbluetoothTestApp=true lintDebug
```

- Debug／AndroidTest／Release：BUILD SUCCESSFUL；JVM **46 tests、0 failures、0 errors**。
- SM-F7410／R5CX71D1SKD／Android 16：NativeCScreenTest **11 tests、0 failures、0 errors**。保留九項原語意測試，新增主面／取消分離與淺深色 snapshots，以及預覽金鑰遮蔽／永久聲明測試。直接驗證 tonal 前景／底色／icon 的 enabled/disabled 狀態值，filled primary/onPrimary 配對，enabled 標籤對比至少 **4.5:1**。
- 最初版曾有 **9 tests／2 failures**（折疊控制未展開、compact 主面被擠小），修正後通過。另一次裝置休眠造成 NoActivityResumedException；喚醒／dismiss-keyguard 後重跑通過，不以該次失敗宣稱功能正常。
- 獨立 lintDebug：BUILD SUCCESSFUL，**0 errors／33 warnings**（包含既有 hardcoded text 等警告），未宣稱零警告。
- aapt2 確認 debug package 為 `com.example.talktoagent.bluetoothdebug`，launcher 仍為 MainActivity；release manifest 不含 NativeCPreviewActivity。最後 adb install -r 回傳 Success、實際啟動 debug preview；未操作正式套件、未 clear data／卸載正式 app。
- Logs：`app/build/native-c-build.log`、`native-c-connected.log`、`native-c-lint.log`；JUnit／HTML 報告沿用既有 build 路徑。沒有 Git commit 或 push；既有 prototype／未追蹤規格文件不變。

### 實機截圖（已實際開圖檢視）

所有截圖均為模擬資料、同一實機；主畫面 snapshots 直接掛載 NativeCScreen，完整 debug host 另截。測試透過 MediaStore 寫入 `Pictures/TalkToAgent-native-c`（只供保留截圖、不接線任何正式功能），adb pull 後保存以下九張：

| 場景 | 截圖 |
|---|---|
| READY／淺色 | [ready.png](images/native-c/ready.png) |
| RECORDING／淺色、啟用取消 | [recording.png](images/native-c/recording.png) |
| 未就緒／淺色、停用主面 | [blocked.png](images/native-c/blocked.png) |
| UNKNOWN／深色、ready=true、1.3 倍字體 | [dark-large-font.png](images/native-c/dark-large-font.png) |
| RECORDING／深色 | [dark-recording.png](images/native-c/dark-recording.png) |
| 未就緒／深色、停用主面 | [dark-blocked.png](images/native-c/dark-blocked.png) |
| 金鑰面板／淺色 | [key.png](images/native-c/key.png) |
| 金鑰面板／深色 | [dark-key.png](images/native-c/dark-key.png) |
| 完整 debug host／深色、控制收合 | [debug-host.png](images/native-c/debug-host.png) |

確認 enabled 標籤不再出現白字／淺底的問題；停用呈灰色、保留原位置，主面與取消以空白分離。UNKNOWN 警告在 ready=true 仍可見。這些視覺檢查不是人體操作或真實語音驗收。

## 明確尚未驗證

- 左手實際伸距、握持、盲操作、實際誤觸：**未實測**；自動點擊不能替代。
- 三鍵導覽下的實際佈局／點擊、手勢操作人體驗收、多種小螢幕：**未實測**。已自動驗證 400dp 小可用高度＋1.3 倍字體＋長文字，以及同一實機淺／深色截圖；不宣稱所有字體倍率／裝置通過。
- 震動／haptics：**完全不在本 issue 範圍，沒有實作或驗證**。
- 真實 QR、授權、憑證、麥克風、Gemini、45 秒 deadline、生命周期取消、傳送／剪貼簿與電腦貼上：**未接線、未操作、等待 #13**。
- 正式 app 真實功能未做端到端測試；原始 MainActivity／manifest 與既有使用者資料未修改。既有髒 prototype／規格文件保留原樣，不包含於本次實作範圍。
