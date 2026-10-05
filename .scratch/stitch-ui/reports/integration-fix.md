# Stitch 整合修正交付

狀態：implementation-complete（human-validation-pending）；獨立複查由父方安排。

## 環境與範圍

第一個工具命令以 PowerShell 單引號 command 讀取環境，實際輸出：

```text
PI_PROVIDER=openai-codex
PI_MODEL=gpt-6.1-sol
PI_REASONING_LEVEL=medium
```

符合指定配置。主目錄 `D:/TalkToAgent`，分支 `feat/stitch-native-ui`，base `ddd3fa476d4fd200c6130bef23515db55de8bffb`。未更换模型、未 push、未繞 hooks。提交 SHA 由 `git log -1 -- .scratch/stitch-ui/reports/integration-fix.md` 取得，避免自引用。

已讀 implement、TDD（tests／mocking）、diagnosing-bugs、code-review、commit 技能，官方 android-cli 技能與 references/interact.md，並執行 skills list、layout／screen／screen capture help；讀本地 Stitch／Minimalist／QR 規格、三票、02／03 reports、CONTEXT、相關 ADR，以及父方 integration-review.md。父方報告保持唯讀。沒有 Agent／SubagentWorkflow 工具，最後採本地雙軸 review，不宣稱獨立審查。

使用者已明確總授權：預先同意測試 seam、實作、正常提交及本次分頁文案補充，不逐次詢問。範圍僅 QR 正式接線覆蓋、原類別分頁 helper／文案與兩項矩陣測試修正，沒有重構整個 App。

## 1. QR 正式回呼／結果链

新增 `InvitationScanIntegrationTest`，5 個原生整合案例：

1. 正式 scanner 已註冊的 `decodeSingle` 回呼收到有效邀請，回傳 `RESULT_OK` 與原樣 payload；同一 foreground turn 的第二份有效解碼不能覆寫第一份結果。
2. 無效外部文字顯示正式錯誤，Activity 仍 RESUMED；取消只回 `RESULT_CANCELED`、無 payload，既有授權不變。
3. 離開前景／取消後的晚解碼不能交付成功結果，既有授權不變。
4. 從正式 MainActivity 裝置連線 → 掃描入口開始；無效輸入不開 transport、不變更授權。依錯誤提示取消，使用者明確重開掃描後，有效同 pin Wi-Fi 邀請走正式 Activity result／request 40 → parse／pin → selectInvitation → connectSelected → 真實 Wi-Fi transport。裝置內 `127.0.0.1` 可控 ServerSocket 觀察到 `GET /ws HTTP/1.1`、正式 pending 指向該端點、舊 active 保留；晚解碼不再連線，也沒有自動重試。端點不提供成功授權，畫面仍為未連線，不假報輸入就緒。
5. 格式合法但 fingerprint 不同的邀請可由 scanner 回傳，MainActivity 必須顯示拒絕原因、保留原 active／pending。

最小 seam 僅是 `InvitationScanActivity.scannerFactory` 的相機 widget 建構邊界，預設 `DecoratedBarcodeView::new`，正常執行仍用真正 bundled ZXing、QR-only DecoderFactory、權限 gate、FLAG_SECURE 與原回呼。測試替換外部相機 widget，取得正式註冊的 callback；不反射呼叫 private handler，不另造回呼／授權狀態機，不增加測試 Intent／外部入口。單用 DecoderFactory 仍需要不可控相機 frame；因此採已核准的最小外部 camera seam。CameraInput 不啟動光學相機，刻意允許發送已排隊晚回呼以驗 lifecycle／delivered 防線。

測試以既有公開 AuthorizationStore API 建立合成的 active pin，執行真實 Keystore／metadata；每項備份並恢復原加密 metadata，不讀出私鑰、不改協定、不把合成授權當成完成 Receiver 授權。邀請使用既有 test asset，無真實金鑰／使用者電腦／Gemini 連線。有效主流程遵循 ZXing decodeSingle 一次解碼後需取消／重新掃描的原行為，不藉測試輸入加入自動重試。

### RED／GREEN 與負向控制

- 先寫新整合測試再建置：4 個 compile errors，缺 scannerFactory；這是編譯 RED，不冒稱 runtime RED。
- 加入唯一相機建構 seam：QR 單檔 `OK (5 tests)`。
- 暫時將正式 `setResult(RESULT_OK, payload)` 改為 CANCELED：有效 payload 與 Main loopback 兩項 **2 tests／2 failures**。有效 payload 斷言 expected -1、actual 0；Main 未連到 loopback。
- 恢復 scanner，暫時將 MainActivity request 40 接收 gate 改為 41：正式 Main loopback **1 test／1 failure**，Future 等待外部 transport 超時。足以證明測試不能在切斷正式結果接線時仍綠燈。
- 上述負向修改已全部還原；MainActivity 最終無 diff，正式結果／parse／pin／授權協定未變。
- 首次 19 項矩陣只有新 fingerprint 測試因相同錯誤出現在兩個 blocker 而 matcher ambiguous；改為在正常捲到標題後，對 ScrollView 的可見 descendant 作文字斷言，不修改產品、不刪错误斷言。亦將 loopback 的 bounded wait 起點放在有效 decode 之後，避免把 UI 導覽時間算進 accept timeout。

## 2. 分頁樣式與導航

在 `NativeCScreen` 原類別新增小型 `styleTab`，首頁與連線頁共用 icon／selected／enabled、22dp iconSize、2dp iconPadding、TEXT_TOP、綠／MUTED、10sp、透明無框線；事件與 64dp bounds 仍在原呼叫處，不增加新 navigation 架構。

CONNECTION 左分頁可見 label 改為「語音輸入」，維持 HOME 事件與 contentDescription「語音輸入，返回主畫面」。設定／金鑰等獨立返回按鈕仍為「返回主畫面」。既有 ConnectionLifecycleUiTest／NativeCProductionTest 改用返回語意 matcher，保留原導航／重建覆蓋。

擴充原 tabs 測試：檢查兩頁選取、22dp icon、tint／文字色、字級、無框線與背景透明，連線左 tab 名稱／未選取與實際返回。先跑測試：**1 test／1 failure**，連線頁 expected 66px（22dp）、actual 54px（18dp）。helper 後於完整原生及矩陣 GREEN。忙碌入口停用斷言由既有 suite 保留。

## 3. 320dp／字體 2.0 矩陣根因

先在 `emulator-5556` 的 960×1920px／density480（320×640dp）／font_scale2.0／gestural／show_ime_with_hard_keyboard1 執行指定兩 suite，真實重現 **14 tests／2 failures**：

- LiveVoiceScreenTest:105：等待辨識文字的 global visible rectangle 為空，因資訊區已超出 viewport，並非文字未更新。以正常 Espresso `scrollTo` 後驗可見，其他 phase 的資訊斷言亦明確正常捲動；保留 retained node、live-region、waveform、scroll／觸控與 SENDING 語意斷言，不縮字、不删斷言、不改產品版面。
- StitchSecondaryScreenTest:116：click 已使金鑰欄位 focused，但立即 replaceText 時，IME resize 令 global visible rectangle 為空。先拆開 click 與輸入，保留既有真 IME visible／5 次穩定 insets 輪詢，再正常 scrollTo focused 欄位、assert 完整顯示並輸入／讀回；真 swipe 後驗提交與返回完整可見且 IME 仍顯示，保留實際點擊。沒有以重跑或停用動畫當 fix。

假設排序：資訊缺捲動前提、IME 轉場時序、產品欄位真不可達。前兩項變更後欄位可正常捲到、輸入、提交與返回，故未做產品 IME 版面修正。單檔連續 3 次 GREEN（10.446s／10.279s／10.256s），後續擴充 19 項矩陣亦多次 GREEN。

## 最終執行環境與命令

- JAVA_HOME=`D:/andriodStudio/jbr`
- ANDROID_HOME／ANDROID_SDK_ROOT=`C:/Users/Ta.Hsiung/AppData/Local/Android/Sdk`
- 官方 CLI=`SDK/cmdline-tools/15859902/bin/android.exe`
- **僅操作 emulator-5556，API36**。所有 adb 均明確 `-s emulator-5556`，CLI 均 `--device=emulator-5556`。沒有操作5554或執行未篩 serial 的 connectedDebugAndroidTest。
- runner=`com.example.talktoagent.test/androidx.test.runner.AndroidJUnitRunner`
- 原生 runner 前停止 `com.android.cli.interact.instrumentation` 避免 UiAutomation 衝突。

PowerShell 設定上述三個 env 後，主要命令：

```text
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am force-stop com.android.cli.interact.instrumentation
adb -s emulator-5556 shell wm size 960x1920
adb -s emulator-5556 shell wm density 480
adb -s emulator-5556 shell settings put system font_scale 2.0
adb -s emulator-5556 shell settings put secure show_ime_with_hard_keyboard 1
adb -s emulator-5556 shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
adb -s emulator-5556 shell am instrument -w -e class com.example.talktoagent.LiveVoiceScreenTest,com.example.talktoagent.StitchSecondaryScreenTest,com.example.talktoagent.InvitationScanIntegrationTest com.example.talktoagent.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w com.example.talktoagent.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell pm revoke com.example.talktoagent android.permission.CAMERA
adb -s emulator-5556 shell am instrument -w -e class com.example.talktoagent.StitchSecondaryScreenTest#scannerWithoutCameraPermissionCancelsWithoutReturningAnInvitation com.example.talktoagent.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell pm grant com.example.talktoagent android.permission.CAMERA
```

完整 suite 前還原1170×2466／font1，分別切三鍵與手勢；相機 revoke 在 runner 啟動前，不在 runner 內殺 target。

| 驗證 | 實際結果 |
| --- | --- |
| JVM | 62 tests／0 failures；XML 加總核對 |
| Debug APK、AndroidTest APK、lint | BUILD SUCCESSFUL；lint 0 errors／18 warnings，非 warning-free |
| 最終三鍵完整原生（390×822dp／font1） | OK (40 tests)，300.346s |
| 最終手勢完整原生（390×822dp／font1） | OK (40 tests)，301.130s |
| 小視窗／font2 手勢，14 原有＋5 QR | 三次 OK (19 tests)：190.541s／186.450s／148.428s；最後一次包含 cancel 再明確重掃的最終測試版本 |
| 真鍵盤多次單檔 | 3 次 OK (1 test) |
| 外部撤銷 CAMERA 後獨立測試 | OK (1 test)，46.642s |
| git diff --check | 通過 |

完整40項的無相機條件測試在已授權時 assumption 略過；另以獨立 denied invocation 實際驗證，不把略過當成相機拒絕已通過。

最後已核對並恢復5556：override size **1170×2466**、density **480**、font_scale **1.0**、**gestural overlay enabled**，show_ime_with_hard_keyboard **1**、CAMERA 已重新 grant。物理基底1080×2400／density420不變。沒有改系統動畫或其他裝置。

## 截圖／證據

`.scratch/stitch-ui/screenshots/integration-fix/` 四張已逐圖實際視覺檢查：

- home-tabs.png：正式 MainActivity／1170×2466／font1，語音選取，裝置未選取。
- connection-tabs.png：正式 MainActivity 同配置，左側「語音輸入」／右側「裝置連線」，反向選取；22dp圖示與無框線一致。由官方 CLI layout 定位後操作、screen capture，沒有連真 Windows。
- compact-font2-keyboard.png：960×1920／font2 真 IME、遮蔽測試文字、儲存／清除／獨立返回皆可見，來自第二次 GREEN 矩陣的 ticket03-key-real-keyboard (14).png。
- compact-font2-preview.png：相同矩陣的 ticket02-preview-waveform (3).png，可控服務預覽／音量，資訊正常捲動、結束／取消直接可見；不冒稱真麥克風／Gemini。

沒有新增 QR／邀請圖片或清除 FLAG_SECURE。測試與負向控制的公開結果才是 callback／result-chain 證據；光學掃描仍待人驗。

主要原始輸出保存於本次 `fix-*.txt`；選定輸出由 PowerShell Tee 的 UTF-16 轉為 UTF-8，僅移除 EOF 多餘空行以通過 diff-check，沒有改測試結果文字。提交選定 RED／負向／最終輸出與上述四張新證據，不 stage 父方 logs／layout。其餘本次重複跑測試 logs／layout 保留本地但不提交。

## 本地雙軸 review（非獨立 reviewer）

固定 base 已核對，實作前檢視 `git diff ddd3fa4 -- app` 與新增 QR test，提交後可重現 `git diff ddd3fa4...HEAD`。MainActivity 負向控制已還原，最終只有 camera 建構 seam 與 tabs helper 的正式 diff。

### Standards

本地未解硬違反0。外部 seam 僅替換相機邊界，不把驗證／結果／phase 移到測試；測試 camera subclass 忽略真相機 lifecycle 是外部 boundary 替身，不進產品。helper 消除已指出的重複樣式，不引入新模組或 navigation 抽象。無依賴／lockfile／秘密／debug 殘留。測試的可觀察 widgets 與公開 AuthorizationStore 是需求指定 seam；沒有 private method reflection。測試控制 callback、待命 loopback 及真正鍵盤，而非 parser-only 或平行狀態機。

### Spec

本地未解自動阻塞0。QR 成功／無效／晚回呼、payload、正式主畫面 pin／transport 與既有授權保護已有實際 native 證據；負向控制證明切斷正式回傳／request接線會 RED。兩頁 tabs 名稱／視覺一致與 HOME 返回保持；其他返回不變。矩陣修正維持真鍵盤、retained與phase／觸控斷言，不改語音／傳輸／授權協定或自動重試。父方將安排原兩位 reviewer 獨立複查；本文件不代替該結论。

## 仍待人驗／未宣稱完成

- 真實手機光學掃描終端有效／無效 QR、相機手感及完整權限 dialog。
- 真 Gemini key／服務／物理麥克風音量、停止與取消實際收音。
- 真 Windows Wi-Fi／Bluetooth 系統配對、應用授權／撤銷／恢復及貼上；loopback僅證明傳輸已被正式接線啟動，不證明已授權。
- TalkBack 人工瀏覽、握持／觸覺／鎖屏及既有安裝升級驗收。
- 獨立雙軸 review 由父方安排。本修正沒有其他已知自動阻塞。

## 已授權提交提案（add 前記錄）

單一意圖：解除本輪整合回歸缺口，使用者總授權替代逐次確認。中文 Conventional Commit 訊息全文：

```text
fix(android): 補齊掃描正式接線並穩定大字體整合回歸

以外部相機輸入驗證正式結果鏈，統一分頁名稱與圖示，修正捲動前提與鍵盤轉場時序，保留授權與語音行為並補上負向控制及矩陣證據。
```

明確 add 清單：

- app/src/main/java/com/example/talktoagent/InvitationScanActivity.java
- app/src/main/java/com/example/talktoagent/NativeCScreen.java
- app/src/androidTest/java/com/example/talktoagent/InvitationScanIntegrationTest.java
- app/src/androidTest/java/com/example/talktoagent/LiveVoiceScreenTest.java
- app/src/androidTest/java/com/example/talktoagent/StitchSecondaryScreenTest.java
- app/src/androidTest/java/com/example/talktoagent/ConnectionLifecycleUiTest.java
- app/src/androidTest/java/com/example/talktoagent/NativeCProductionTest.java
- .scratch/stitch-ui/reports/integration-fix.md
- .scratch/stitch-ui/reports/fix-red-matrix.txt
- .scratch/stitch-ui/reports/fix-red-tabs.txt
- .scratch/stitch-ui/reports/fix-negative-result.txt
- .scratch/stitch-ui/reports/fix-negative-main-wire.txt
- .scratch/stitch-ui/reports/fix-final-build.txt
- .scratch/stitch-ui/reports/fix-final-matrix-2.txt
- .scratch/stitch-ui/reports/fix-final-matrix-3.txt
- .scratch/stitch-ui/reports/fix-final-native-threebutton.txt
- .scratch/stitch-ui/reports/fix-final-native-gestural.txt
- .scratch/stitch-ui/reports/fix-final-camera-denied.txt
- .scratch/stitch-ui/screenshots/integration-fix/home-tabs.png
- .scratch/stitch-ui/screenshots/integration-fix/connection-tabs.png
- .scratch/stitch-ui/screenshots/integration-fix/compact-font2-keyboard.png
- .scratch/stitch-ui/screenshots/integration-fix/compact-font2-preview.png

明確排除：docs/prototype/uiux/*、docs/issue-9-agent-brief.md、docs/spec-minimalist-ui.md、docs/spec-qr-connection-invitation.md、.idea/；父方 integration-review.md、所有 integration-*.txt／json、既有未追蹤 screenshots與commit helpers／red目錄；所有不在上列的本次 fix logs／layout、commit訊息檔、Gradle／APK產物及其他未追蹤檔。本次 MainActivity 無最終變更，不 add。未修改／stage上述禁止路徑，未將父方 logs當本次證據。
