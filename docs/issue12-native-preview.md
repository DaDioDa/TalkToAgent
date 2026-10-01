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
- `render(State)` 顯示主畫面並預設隱藏定稿。主畫面不捲動：電腦／就緒摘要限制單行，phase、期限、即將結束提示各有固定保留列；三個次要入口採水平排列、48dp 高。障礙只佔一個固定 48dp 修復入口（長文截斷），完整狀態與所有障礙／修復入口在可捲動次頁展示。上方資訊以 196dp 為基底、主區以 48dp 為基底平均分配剩餘空間，幾何不隨狀態內容變動；底部取消列 68dp、分隔 12dp。system bars／cutout／IME insets 留在控制之外。
- 次頁導覽由同一畫面處理，並輸出 CONNECTION／SETTINGS／LATEST／DIAGNOSTICS／KEY／PERMISSION／HOME 事件。內部導覽先完成，再發布事件，因此 callback 同步呼叫 render 時 host 快照具有最後決定權，不會被舊次頁覆寫。次頁可捲動。render 新快照會回主畫面；host 可以在忙碌開始時直接 render。
- START／FINISH／CANCEL 只輸出意圖，不自己轉換 phase。取消左／中／右共用同一獨立 hit area。
- SCAN_QR、RECOVER_AUTHORIZATION、BLUETOOTH、WIFI、DISCONNECT 提供 QR／授權恢復／明確通道／主動中斷的位置，沒有舊 IP／配對碼欄位。
- COPY 帶 latestFinalText，MANUAL_SEND 帶手動輸入，SAVE_KEY 帶新輸入，CLEAR_KEY／REQUEST_PERMISSION 無 payload；其餘事件 text 為空。畫面不存取任何真實系統服務。金鑰不顯示已存值，不保存 view state，送出後清空欄位；預覽事件標籤不顯示金鑰 payload。
- 權限修復不自動開始；完成文案不宣稱目標程式接受文字；UNKNOWN 不重送、不增加強制確認對話框。

## 預覽檢查方法

頂端永久標示「開發預覽／模擬資料」。選「切換模擬狀態」直接選十個 enum；選「切換模擬就緒／障礙」獨立改 ready；「選擇障礙原因」可選金鑰、權限、連線或多項長文；「期限 45／5 秒」直接選期限。沒有自動倒數或平行語音流程。操作事件在標籤可見，不代表功能完成。模擬 selectors 在忙碌時仍可用，屬於 debug host，不是正式次要入口。

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

## Review 修正：同步 render 與小高度核心狀態

僅修正兩個已驗證問題，未變動真實功能或 MainActivity。

- 公開 UI 測試 `synchronousHostRenderWinsOverConnectionNavigation`：CONNECTION callback 同步 render(PREPARING)，最後必須顯示 busy 主畫面、主操作停用、沒有掃描 QR。修正前 `NoMatchingViewException`（準備狀態被次頁覆寫）；調整為先導覽再事件發布後通過。COPY／MANUAL_SEND payload 測試仍通過。
- 擴充 `compactHomeLongReasonsDoNotMoveControlsAndCancelEdgesOnlyEmitCancel`：400dp、兩個障礙含長原因，在 PREPARING／RECORDING 皆以 `isCompletelyDisplayed` 確認 phase／5 秒期限／即將結束提示不需捲動，主區與取消矩形穩定且至少 48dp。修正前核心狀態的完整可見性 assertion 失敗；移除 home ScrollView，保留固定資訊列並將次要入口改水平、長補充內容留次頁後通過。
- 本次先跑同一單檔取得 **9 tests / 2 failures** 的 red；實作後同一單檔 **9 tests / 0 failures / 0 errors** 的 green（SM-F7410，包含 Java／AndroidTest 編譯與 APK 打包）。證據暫存於 `app/build/issue12-review-red.log`、`app/build/issue12-review-green.log`。修正後已重跑完整 Android suite（46 JVM／15 instrumentation）、Release build、獨立 lintDebug，以及 Receiver 36 項與授權互通 2 項；全部通過。另重新檢查 release manifest 無預覽 Activity，並在手機重裝、啟動並存預覽版。

## 明確尚未驗證

- 左手實際伸距、握持、盲操作、實際誤觸：**未實測**；自動點擊不能替代。
- 三鍵導覽下的實際佈局／點擊、手勢操作人體驗收、不同字體倍率／多種小螢幕：**未實測**。已自動驗證 400dp 小可用高度＋長文字，但不宣稱所有裝置通過。
- 震動／haptics：**完全不在本 issue 範圍，沒有實作或驗證**。
- 真實 QR、授權、憑證、麥克風、Gemini、45 秒 deadline、生命周期取消、傳送／剪貼簿與電腦貼上：**未接線、未操作、等待 #13**。
- 正式 app 真實功能未做端到端測試；原始 MainActivity／manifest 與既有使用者資料未修改。既有髒 prototype／規格文件保留原樣，不包含於本次實作提交。
