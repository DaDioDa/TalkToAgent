# Ticket 02 實作與驗證報告

## 模型、分支與基準

第一步以 PowerShell 確認實際環境：

```text
PI_MODEL=gpt-6.1-sol
PI_PROVIDER=openai-codex
PI_REASONING_LEVEL=medium
```

符合指定 openai-codex/gpt-6.1-sol／medium，沒有更換模型。首次指令因外層 shell 展開 `$env` 而語法失敗，改用單引號後取得上述值，確認後才開始修改。

- 隔離 worktree：`C:/Users/TAC049~1.HSI/AppData/Local/Temp/pi-agent-58e9bc8e-11aa-4a4-2f02a62c`
- 分支：`feat/stitch-ticket-02`
- Base：`be3ca697e442fa1ed6cc14799b08a546f9e23ce7`（Ticket 01 完成點）。`git merge-base --is-ancestor be3ca69 HEAD` exit **0**，提交前 HEAD 即 base。
- 狀態：**implementation-complete（human-validation-pending）**。
- 提交 SHA 可由 `git log be3ca69..HEAD --oneline` 取得；不在報告內自引用 SHA。
- 僅唯讀原目錄 `D:/TalkToAgent/docs/spec-minimalist-ui.md`；沒有複製／提交原目錄未追蹤規格，也沒有修改主目錄、合併新變更或操作其他 worktree。

已完整讀 implement、tdd（含 tests／mocking）、code-review、commit 技能，以及本票索引、票、Stitch 規格、CONTEXT、01 report、三份設計素材、相關 ADR。沿用已核准的 VoiceUtterance／正式辨識回報及 NativeCScreen State/Event seam，不建立平行語音狀態機。

## 正式資料路徑

1. **辨識預覽**：Gemini 原有 `interimInputTranscription.text` → MainActivity 正式 listener → `VoiceUtterance.interim(session, text)` → NativeCScreen retained transcript。每次為可修訂 hypothesis，**替換**目前預覽，不任意拼接；空字串恢復「等待辨識文字」。只有有效 session 的 RECORDING 接受預覽；停止後依原有 transcriber `!released` 語意凍結本次預覽，WAITING 明示「未定稿、尚未送出」。
2. **真實音量**：同一 AudioRecord 既有 3200-byte／16kHz mono PCM16 little-endian buffer → 唯讀 `PcmVolume.level` normalized RMS → main-thread `onVolume` → 同 session、RECORDING 的 32 筆趨勢。沒有第二個 recorder，沒有改動 copy／Base64／上行 payload 或收尾訊息。每條約代表一個既有 nominal 100ms chunk，不是頻譜／校準分貝；UI 以平方根視覺曲線提高低音量可見度，不改辨識音訊。
3. **階段隔離**：PREPARING 不接受音量；finish 清除音量但保留預覽；cancel／fail／leave 清 preview 與音量；新段 start 清舊資料；session generation 拒絕舊段回呼。500ms 沒有新有效樣本時靜止，既有 200ms UI clock 做可用性刷新，不產生假訊號。
4. **定稿**：只有既有 FinalTranscript 收尾結果進入原有有效性／繁體轉換／VoiceUtterance 單次提交。預覽不寫 latest、診斷、Bundle 或持久歷史；無效定稿不拿預覽充數。未改 Receiver、授權、transport 協定、deadline、haptic 或亮屏語意。
5. **局部呈現**：`updateLive` 不 render／removeAllViews、不 requestFocus、不啟動畫；只在文字／標題／色彩變化時更新 transcript，在固定 32dp bar bounds 上改 scaleY／alpha。預覽區預留三行高度，避免文字修訂令卡片伸縮；最近定稿的原有高度保留。相同呈現的期限快徑未改。
6. **SENDING 修正**：標題為「轉錄定稿 · 傳送中」，本文「正在傳送轉錄定稿」；不聲稱未送出，也不把上一筆 latest 冒充本次正在傳送的文字。COMPLETED／UNKNOWN 等結果仍由正式 phase 映射。
7. **外部測試邊界**：新增最小 `VoiceRecognition`（release／close／finalizeAtDeadline）與 MainActivity recognitionFactory，預設仍為 GeminiLiveTranscriber。原生測試只替換外部辨識／傳輸，實際使用正式開始、停止、取消、生命週期 handler、VoiceUtterance 及首頁；沒有測試專用旁路。

本票只做既有 API 使用語意的精準查證，不是研究交付。以 `ketch scrape` 讀 [Google 官方 Live transcription](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe) 的「Interim and finalized transcriptions」：interim 是 rapidly updated speculative partial hypothesis；官方例 `renderInterimPreview(text)`，final 才 `commitFinalTranscript(text)`。其 PCM16 little-endian／16kHz／100ms 說明亦與既有實作相符；未借機更改 manual VAD 或收尾協定。

## TDD／RED、GREEN 與修正

- 先寫 `LiveVoicePreviewTest.hypothesesReplaceAndFreezeWhileOnlyFinalIsSubmittedOnce`，執行單檔，**RED**：既有 VoiceUtterance 沒有 session interim／preview API（6 compile errors）；加入最小正式預覽處理後單檔 **GREEN**。
- 接續 volume／舊段／退出案例，**RED**：volume／trend／hasVolume 不存在（18 compile errors）；加入正式音量趨勢與階段 gate。再寫 PcmVolume 已知 signed-LE／silence／RMS／不變更 bytes 案例，**RED**：PcmVolume 不存在（5 compile errors）；實作後兩檔 **GREEN**。無資料過期測試後加作回歸。
- 先寫正式畫面 retained update／預覽及 SENDING 文案測試，**RED**：`updateLive` 不存在；實作後 API36 原生單檔 **GREEN**。追加高頻手指 DOWN／UP、保留捲動、所有非收音階段、缺資料、靜音、320dp／font2 長預覽回歸。
- 先寫正式 MainActivity 回報整合測試，**RED**：外部辨識邊界／factory／onVolume 不存在；另發現本 repo 沒有 GrantPermissionRule dependency，改用已存在 UiAutomation grantRuntimePermission，不新增測試 library。實作正式接線後 **2 tests GREEN**。
- 上述最初 RED 是**編譯 RED**，不冒稱已有 runtime assertion failure。另做有效負向控制：暫時切斷 MainActivity 正式 interim 轉送，保持其餘路徑，原生整合 **Tests run: 2, Failures: 2**（找不到修訂後預覽／新段）；恢復正式接線後兩項及完整套件通過。負向修改沒有留下或提交。
- 完整套件第一次 **25 tests／1 failure**：SENDING 若展示 `latestFinalText`，既有十 phase 隔離測試會找到上一筆定稿。移除這個錯誤呈現，保留真實傳送提示後完整 GREEN；没有削弱舊測試。
- 首批截圖在 UI scroll assertion 已通過時仍抓到舊 compositor frame，波形被舊畫面裁切。新增 capture helper 等兩個 frame 與 bounded UiAutomation idle，直接捲到 waveform 並 assert **isCompletelyDisplayed**；重新擷取、逐圖查看後波形及停止短線完整。不是用 HTML／裁貼圖當原生證據。
- PowerShell 腳本初次被本機 execution policy 擋下，僅以本次程序 `-ExecutionPolicy Bypass` 執行測試 helper；沒有改系統 policy，也沒有繞 git hooks。

## 最終測試

環境：JAVA_HOME=`D:/andriodStudio/jbr`；ANDROID_HOME／ANDROID_SDK_ROOT=`C:/Users/Ta.Hsiung/AppData/Local/Android/Sdk`。已執行官方 `.../cmdline-tools/15859902/bin/android.exe skills list`，完整讀 android-cli SKILL 與 references/interact.md，執行 layout／screen help。

**本票只操作 `emulator-5554`，API36／WHPX。沒有使用 connectedDebugAndroidTest，沒有安裝到其他 AVD。**

PowerShell 設上述環境後：

```text
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.example.talktoagent.test/androidx.test.runner.AndroidJUnitRunner
```

最終結果：

- **BUILD SUCCESSFUL**；JVM **62 tests／0 failures**（原有 57＋本票 5）。
- 原生完整 runner：**OK (25 tests)**，0 failures，188.674s。包括 AuthorizationPersistenceTest、ConnectionLifecycleUiTest、NativeCProductionTest、NativeCScreenTest、StitchVoiceHomeTest、TraditionalVoiceDeliveryTest、本票兩個新 suite 及既有基本測試。
- Lint 成功：**0 errors／17 warnings**，不聲稱 warning-free；`app/build/reports/lint-results-debug.html`／`.xml`。
- `git diff --check` 通過。
- 原有期限、準備失敗、定稿、取消／離開、就緒、單次提交、結果不明與繁體處理測試全部通過。
- 本票 JVM 覆蓋修訂、停止後凍結、preview-only 不送出、無效定稿、失敗／離開清除、舊 session 混入、新段、真實 RMS 計算、silence、不同音量、PCM 不變更、缺樣本過期。
- 本票原生覆蓋正式 MainActivity 接線、準備→收音、文字修訂、音量高低差、finish 靜止、取消後新舊回呼交錯（stage／capture／interim／volume／final／error）、有效定稿只送一次、無效定稿與 failure、離開後晚回呼；另驗 retained nodes、100 次高頻更新期間的 FINISH／CANCEL 真正觸控事件、捲動不重設、所有非捕捉 phase 靜止與 320×480dp／fontScale2 長預覽下控制可見。
- JVM XML：`app/build/test-results/testDebugUnitTest/`。原生原始輸出本地保留 `.scratch/stitch-ui/ticket-02-native-final.txt`（UTF-16 tee，不提交）。沒有把 adb exit 0 當通過：以 runner 的 `OK (25 tests)` 判定。
- 導覽沿本票 AVD 的三鍵模式；未操作其他 serial。本票未重新做手勢模式全套，Ticket01 有其基線證據。
- 完成時明確恢復本票 5554：wm size **1170×2466**、density **480**（390×822dp）、font_scale **1.0**。小視窗／大字體案例透過原生 configuration/frame，未污染裝置系統設定。

## 原生截圖及設計比對

目錄 `.scratch/stitch-ui/screenshots/ticket-02/`：

| 圖 | 證據 |
|---|---|
| ticket02-no-fragment.png | 本次收音尚無片段，不顯示舊定稿／示範句 |
| ticket02-preview-waveform.png | 本次修訂預覽、32 條可控音量趨勢、結束／取消；資訊區已捲到完整波形 |
| ticket02-waiting.png | 凍結預覽、未定稿／未送出標示、全部靜止短線 |
| ticket02-cancelled.png | 取消清預覽、保留先前定稿 |
| ticket02-final-result.png | 有效定稿結果，貼上操作完成不等於目標程式接受 |
| ticket02-compact-font2-preview.png | 320×480dp／2 倍字體／長預覽，finish／cancel 固定可見，資訊須捲動 |
| ticket02-production-preview.png | 正式 MainActivity 外部辨識 callback 接到正式首頁 |
| ticket02-production-final.png | 正式 MainActivity 單次提交後 SENDING，標示傳送中而非未送出 |

**全部是原生 instrumentation 畫面；辨識與音量來源是可控回呼，不是實際 Gemini／實體麥克風。** PCM 計算證據來自已知 PCM fixture 的 JVM 測試；原生音量測試另證明 production listener 的低／高值確實改變正式 bars 且停止立即清零，不能把兩者說成已實測物理收音。

已視覺查看來源 screen.png、預覽波形、等待、取消／結果、大字體及正式傳送畫面。有意差異：沿用 01 的本機字體、原創向量、系統安全區及獨立取消膠囊；預覽淡化並明示暫定，reserved 三行防抖；32 bar 為實際時間趨勢而非稿中隨機高低，靜止全部短線；原生階段／期限使資訊可能需要捲動，操作區不捲動。沒有新增字體資產／外部授權、假分貝／頻譜、假文字或動畫。未為波形重寫次頁／showPanel／input 或 NativeCStyle。

## 本地雙軸 review

固定點 be3ca69 可解析；提交前檢查實際 working diff 與所有本票新檔，不拿尚未提交的空 `base...HEAD` 冒充 review。提交後重現 `git diff be3ca69...HEAD`／`git log be3ca69..HEAD --oneline`。

### Standards

依 repo AGENTS、Java Views 契約、ADR、tdd 邊界及 code-review Fowler smell baseline 檢查。沒有未解的硬違反；未新增依賴、秘密、debug 功能或 Receiver 修改。VoiceRecognition 是外部辨識的最小 seam，不承載另一套 phase。判斷項：MainActivity 既有大型 coordinator 仍存在，但本票只增加局部接線；32 值＋available 的小呈現參數群可視為 data clump，現在僅一個 render seam、無多路搬運，不引入多餘框架。修正了音量更新每次重設 transcript color 的不必要 invalidation，現在 unchanged volume 只影響 bars。測試 fixture 的 fake key 僅 test path，成功後清除；無真實金鑰。

### Spec

逐項對照 02，正式資料／session gate、替換假設、PCM 音量、preview 不送出、退出靜止、不中斷點按及 SENDING 文案均有自動證據；沒有 03、編輯預覽、歷史、長按、背景錄音或 Windows scope creep。修正了 SENDING 誤顯上一筆定稿以及 compositor 舊截圖；保留「真實手機／Gemini／Windows」及 TalkBack 的人工項目未勾，不把 mock 當真實驗收。

**本呼叫沒有 Agent／SubagentWorkflow 工具，不能執行兩個獨立 parallel 子代理 review。上述是同一 Sol 6.1／medium 的本地兩軸檢查，非獨立審查；已告知父方須補獨立同模型／medium Standards／Spec review。** 本地 Standards 未解硬違反 0；Spec 自動部分未解阻塞 0，人工驗收及獨立 review pending。

## Human-validation pending

- 使用實際 Gemini 金鑰與 SM-F7410，確認服務 hypothesis 真實序列／延遲、同一 mic 音量變化、靜音／網路失敗、正常停止／取消／離開實際停止收音。
- 使用者 Windows Receiver 只收到有效轉錄定稿，沒有暫定預覽，正常貼上一次與結果不明不重送。
- TalkBack 實際瀏覽文字區域／狀態，不持續朗讀高頻回報；自動已驗 live-region NONE／隱藏 waveform descendants，但不能代替人工輔具體驗。
- 本票 API36／WHPX 自動測試不等於物理 mic／手持、觸覺／真機亮屏驗收。

## 已授權 commit 提案（執行前記錄）

使用者已授權三票完成前不逐次詢問及每票完成 commit，覆蓋 commit skill 逐次確認；不 push、不繞 hooks。一次 atomic 本票 commit，訊息全文：

```text
feat(android): 接入有效語音段的辨識預覽與真實音量趨勢

以既有麥克風資料呈現預覽與音量，隔離過期回報並保持定稿單次提交，讓局部更新不打斷操作；補上正式接線測試與待真人驗收證據。
```

明確 add 清單：

- `app/src/main/java/com/example/talktoagent/GeminiLiveTranscriber.java`
- `app/src/main/java/com/example/talktoagent/MainActivity.java`
- `app/src/main/java/com/example/talktoagent/NativeCScreen.java`
- `app/src/main/java/com/example/talktoagent/VoiceUtterance.java`
- `app/src/main/java/com/example/talktoagent/PcmVolume.java`
- `app/src/main/java/com/example/talktoagent/VoiceRecognition.java`
- `app/src/test/java/com/example/talktoagent/LiveVoicePreviewTest.java`
- `app/src/test/java/com/example/talktoagent/PcmVolumeTest.java`
- `app/src/androidTest/java/com/example/talktoagent/LiveVoiceScreenTest.java`
- `app/src/androidTest/java/com/example/talktoagent/LiveVoiceProductionTest.java`
- `app/src/androidTest/java/com/example/talktoagent/StitchVoiceHomeTest.java`
- `.scratch/stitch-ui/issues/02-live-preview-waveform.md`
- `.scratch/stitch-ui/reports/ticket-02.md`
- `.scratch/stitch-ui/screenshots/ticket-02/ticket02-no-fragment.png`
- `.scratch/stitch-ui/screenshots/ticket-02/ticket02-preview-waveform.png`
- `.scratch/stitch-ui/screenshots/ticket-02/ticket02-waiting.png`
- `.scratch/stitch-ui/screenshots/ticket-02/ticket02-cancelled.png`
- `.scratch/stitch-ui/screenshots/ticket-02/ticket02-final-result.png`
- `.scratch/stitch-ui/screenshots/ticket-02/ticket02-compact-font2-preview.png`
- `.scratch/stitch-ui/screenshots/ticket-02/ticket02-production-preview.png`
- `.scratch/stitch-ui/screenshots/ticket-02/ticket02-production-final.png`

明確排除：`.scratch/stitch-ui/run-gradle.ps1`、`run-native.ps1`、`ticket-02-native-final.txt`、commit 訊息檔、所有 Gradle/build 產物及其他不在 add 清單的檔案；它們是本地 helper／原始輸出而非交付。`docs/spec-minimalist-ui.md`／`spec-qr-connection-invitation.md` 不在 worktree 未追蹤檔補入清單，只按指示必要時唯讀；沒有提交原目錄其他既有未追蹤規格或 UI prototype。

## 父方整合注意

- **MainActivity**：recognition 欄位／factory、clockTick 一行、refreshReadiness 尾端 `updateLiveVoice`、pressToTalk factory 及 onInterim／onVolume listener 小區塊。沒有改 handleAction、連線 handler 或 panel 操作。
- **NativeCScreen**：首頁 retained transcript／volumeStatus 欄位、`updateLive`／`transcriptHeading`、render 的 preview 清除／結尾更新、words heading height／minLines、bars 固定 bounds。沒有碰 showPanel／input／次頁樣式或 NativeCStyle。
- **VoiceUtterance**：在既有 phase/generation 上增加 memory-only preview／32 樣本與各 exit reset，不改傳送與時間狀態機。
- 父方請維持上述局部更新與 session gate，勿在 03 整合時把每次音量回報改回整頁 render；統一 cherry-pick 本票 commit，再補獨立雙軸審查。
