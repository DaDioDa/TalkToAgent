# Ticket 01 實作與驗證報告

## 配置與提交基準

PowerShell 實際輸出：

```text
PI_MODEL=gpt-6.1-sol
PI_PROVIDER=openai-codex
PI_REASONING_LEVEL=medium
```

符合指定配置。第一次呼叫受外層 shell 展開 `$env` 影響而語法失敗；改以單引號傳遞後取得上述實際值，未更換模型。

- 分支：`feat/stitch-native-ui`
- Base：`7fa329984ef4d933aa2318777f17b40c611a1593`
- 規格 commit：`62a2f47756e859daca60094c7e25a017a7651645`
- 首頁實作：`b8cbb49e89fc88fefa2651212f8c9e6f51b135e9`
- 最終實作／測試 head：`6938a1e5e55cf1de739cfbf19d9a4cd59732ef35`（追加長定稿回歸測試）
- 證據 commit 為本報告所屬提交，可用 `git log -1 -- .scratch/stitch-ui/reports/ticket-01.md` 取得，避免自引用 commit SHA。
- 狀態：implementation-complete（human-validation-pending）。

## 實作範圍

正式 Java Views 首頁保留頂列、狀態卡、定稿／等待文字卡、32 條靜止波形、漸層圓形 mic／停止圖示、獨立取消膠囊及底部分頁。以 `NativeCStyle` 集中卡片、圓角、間距及深色角色；NativeC theme 的日／夜模式皆固定指定深色。無 Compose、WebView、CDN、假文字、隨機音量或新增語音狀態機。

`MainActivity` 僅增加 snapshot 的 `connected`／`channel` 參數；真正連線與整體就緒分開，不從電腦名稱推測連線。不改 VoiceUtterance、GeminiLiveTranscriber、金鑰／授權保存、Receiver 或傳輸協定。定稿僅沿用 `latest`，手動測試不覆蓋它；文字卡不推定最近定稿已成功。原有金鑰、權限、QR、授權恢復、通道、診斷、全文與複製事件仍沿用既有接線。

取消、等待定稿、傳送、完成與結果不明仍按既有 phase／ready 映射；取消位置保留、忙碌時禁止次頁。期限更新只更新文字與預警，不在手指按下／放開之間替換主控制。

## TDD／失敗與修正

預先核准 seam：`NativeCScreen.State/Event` 與既有正式語音協調。已完整讀 implement、tdd（含 tests／mocking）、code-review、commit 技能。

1. 首頁定稿摘要 tracer test 先寫、保存舊呈現 APK；模擬器未完成啟動時不能執行 RED，因此先實作。之後把保存的舊 APK 與新測試安裝到 API36，確實因首頁缺少「最近的真實定稿」失敗，再以新版得到 GREEN。這是延後執行 RED，並非宣稱實作前已跑過裝置測試。
2. 390×822dp 與 2.0 字體曝光期限文字無法完整捲入小 viewport；縮小裝飾 halo 至 72dp（主觸控仍 64dp），單檔 GREEN，最後全套 GREEN。
3. 新增「期限刷新期間的結束點按」測試先失敗，修正 timer 呈現快徑。測試另需等待 ACTION_UP 排程的 performClick；修正等待後 GREEN。再暫時停用快徑作 negative control，確實失敗，恢復快徑後全套 GREEN。沒有把 fixture 排程問題當產品通過證據。
4. 同次執行 AndroidTest＋lint 時，Gradle 發現既有 authorization-test-assets 隱含依賴；補齊 lint task 依賴後全套成功。

## 測試環境與結果

- JDK：`JAVA_HOME=D:/andriodStudio/jbr`
- SDK：`ANDROID_HOME`／`ANDROID_SDK_ROOT=C:/Users/Ta.Hsiung/AppData/Local/Android/Sdk`
- 官方 CLI：`.../cmdline-tools/15859902/bin/android.exe`；最初 PATH 的 android 不存在，後以此明確路徑執行 `skills list`。已完整讀 android-cli 與 references/interact.md，先跑 layout／screen help。
- 父方建立的 AVD：`TalkToAgentStitchApi36`，API36 AOSP／WHPX，serial `emulator-5554`。本代理未安裝 SDK 或建立 AVD。
- 視窗：`adb shell wm size 1170x2466`、`wm density 480`，即 390×822dp；另有 320×480dp frame、fontScale 2.0、長電腦／阻塞文字的原生測試。
- 導覽：三鍵原生全套；手勢原生全套先前 17 項通過。最後新增的期限觸控與長篇定稿驗證在三鍵跑通；安全區測試兩種模式皆跑通。

最終命令（PowerShell 先設定上述環境）：

```text
./gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug :app:lintDebug --console=plain
```

**結果：BUILD SUCCESSFUL；JVM 57 tests／0 failures；原生 19 tests／0 failures。** 包含 NativeCScreenTest、NativeCProductionTest、ConnectionLifecycleUiTest、StitchVoiceHomeTest、AuthorizationPersistenceTest、TraditionalVoiceDeliveryTest。JVM 既有期限、單次提交、取消／離開、過期回呼與就緒測試未修改，仍通過。

- 原生 XML：`app/build/outputs/androidTest-results/connected/debug/TEST-TalkToAgentStitchApi36(AVD) - 16.xml`
- JVM：`app/build/test-results/testDebugUnitTest/`
- Lint：`app/build/reports/lint-results-debug.html`（命令成功；不宣稱零 warning）
- 本地原始輸出：`ticket-01-red.txt`、`ticket-01-touch-red.txt`、`ticket-01-touch-negative-control.txt`、`ticket-01-final-tests.txt`；Windows tee 的文字編碼可能影響終端中文，不影響 App 或 XML 字串。
- `git diff --check` 成功，僅 CRLF 自動換行告知。

## 截圖與視覺差異

提交的截圖位於 `.scratch/stitch-ui/screenshots/ticket-01/verified/`：十個 phase、320dp／2 倍字體長文字、2 倍字體長定稿全文／複製、連線、診斷草稿、手勢就緒及 `design-comparison.png`。十個 phase 是 instrumentation 透過正式 NativeCScreen 契約提供的測試 snapshot，不是真實雲端辨識。`production-three-button.png` 為另以官方 CLI 啟動正式 MainActivity 的未就緒實際畫面（本地保留，未列入提交）。

已視覺查看設計並列、就緒、收音、小字窗／大字體與正式未就緒畫面。初版待機波形卡被裁切，移除重複的「等待開始」行後已完整露出；活動狀態額外期限／原因使資訊可捲動，主操作與取消始終固定。

有意差異：

- 保留 Android 真實狀態列及三鍵／手勢安全區，沒有複製稿中無系統列的高度。
- 移除頭像、模式切換及示範資料；狀態卡因此較稿短，修復條件較多時變長。
- 字體採離線可用的 Android 本機字體（sans／monospace），未下載 Space Grotesk、Geist、JetBrains Mono；品牌以既有本地 mic 向量替代外部 logo。設定使用文字入口，裝置圖示採本次原創幾何向量，不是 Google Symbols 字形。
- 新增 stop／devices 向量為原創基本幾何，無新增第三方素材或字體授權。正式介面不載入外部素材。
- 取消維持膠囊但沿用 outlined button；波形為 32 條一致靜止短線、明示尚未接入，不能解讀為有效測量。
- 小視窗／大字體壓縮 mic／halo、隱藏品牌副標；長名稱截短，連線頁保留完整名稱；必要狀態可捲動完整閱讀。

## Code review

固定點已解析；提交前檢查 `git diff 7fa3299 -- <本票 paths>`，因工作尚未提交，不能將空的 `base...HEAD` 說成已審查。標準來源：repo AGENTS.md、既有 Java／Material Views 契約、既有 ADR；規格來源：本地 Ticket 01、spec-stitch-ui.md、CONTEXT.md、三份設計輸入。

### Standards

本地檢查全部本票變更及 Fowler smell baseline。沒有發現未解決的硬標準違反；重複 busy 判斷整理為 isBusy／mainHint，小共用樣式限制在 NativeCStyle，不建立額外框架。MainActivity 只改 snapshot，不改語音／授權協調。沒有秘密、debug 假功能、lockfile 或 Receiver 變更；圖像／測試資料只在測試或證據。

### Spec

提交後固定點差異亦可重現：`git diff 7fa329984ef4d933aa2318777f17b40c611a1593...6938a1e5e55cf1de739cfbf19d9a4cd59732ef35`。commit 清單為前述規格、首頁實作與長定稿回歸三筆；最後追加測試亦已本地檢查，未增加產品行為。

本地逐項對照 01，修正了待機波形裁切、不可用時的開始提示、長名稱及 timer 觸控中斷。02 的真實 preview／音量與 03 的次頁完整視覺保持未實作；不改兩票依賴。真實手機／雲端／Windows 項目明列待驗收。

**限制：本呼叫沒有 Agent／SubagentWorkflow 工具，無法執行技能要求的兩個獨立 parallel review 子代理。以上是本地雙軸檢查，不冒稱獨立 review；已告知父指揮官。** 本地 Standards 未解硬違反 0；Spec 剩餘為下列 human-only 驗收與素材／視覺替代。父方可另派指定模型／medium review。

## 02／03 可重用 seam

- `State/Event` 與 `Phase` 不改語意；新增完整 State overload 明確傳入 connected／channel，舊 overload 保留。
- `transcript` 與 `waveform` 保留原生節點引用，並有 `voice-transcript`／`voice-waveform` tag；02 可在 NativeCScreen 加局部呈現更新、由 MainActivity 隔離過期 session 後呼叫，不需要重建首頁。真正資料／session 防護仍屬 02，01 沒有接假回報。
- `sameTimerPresentation`／`updateDeadline` 已證明局部更新不打斷 finish tap。
- `NativeCStyle.card/rounded/dp` 與深色 theme／既有 TextInputLayout、button inflater 可供 03 重用；金鑰／診斷 update 不清除草稿。

## Human-only／未驗證

- 實際手機＋Gemini 雲端金鑰的開始／停止／取消與 Windows Receiver 貼上、真實音訊與服務結果。
- 手持單手誤觸、震動感受、真機鎖屏／亮屏、TalkBack 人工朗讀體驗。
- 真實 Windows QR／藍牙配對／授權恢復與升級既有安裝資料保留；自動 Keystore／授權 persistence 與正式原生導覽測試已通過，但不是完整真人端到端。
- 實際手機系統剪貼簿驗收；短／長定稿（2.0 字體）的全文捲動與完整 COPY payload 已有原生測試。
- 02 的即時辨識預覽／音量、03 的完整次頁外觀不屬本票。

## 已授權 commit 提案（執行前記錄）

使用者的總授權取代 commit skill 逐次確認，不 push、不繞 hooks；僅 add 以下明確路徑。

### 1. 保存已確認規格／設計（獨立 docs commit）

訊息：

```text
docs(stitch): 保存已確認的原生 UI 規格與實作票

為讓後續工作樹取得同一份設計依據，保存本輪收斂的領域詞彙、規格與三票，避免把需求確認誤當成功驗收。
```

Add 清單：
- `CONTEXT.md`
- `docs/spec-stitch-ui.md`
- `.scratch/stitch-ui/README.md`
- `.scratch/stitch-ui/issues/01-voice-home.md`
- `.scratch/stitch-ui/issues/02-live-preview-waveform.md`
- `.scratch/stitch-ui/issues/03-secondary-screens.md`
- `stitch_talktoagent/screen.png`
- `stitch_talktoagent/code.html`
- `stitch_talktoagent/DESIGN.md`

### 2. 首頁實作／測試

訊息：

```text
feat(android): 還原深色語音首頁並保留正式操作語意

以既有呈現契約還原卡片與圓形語音操作，分開連線與就緒，讓期限刷新不打斷觸控；保留既有協調流程並以原生回歸測試驗證。
```

Add 清單：
- `app/build.gradle.kts`
- `app/src/main/java/com/example/talktoagent/NativeCScreen.java`
- `app/src/main/java/com/example/talktoagent/NativeCStyle.java`
- `app/src/main/java/com/example/talktoagent/MainActivity.java`
- `app/src/main/res/values/native_c_theme.xml`
- `app/src/main/res/values-night/native_c_colors.xml`
- `app/src/main/res/drawable/native_c_stop.xml`
- `app/src/main/res/drawable/native_c_devices.xml`
- `app/src/androidTest/java/com/example/talktoagent/NativeCScreenTest.java`
- `app/src/androidTest/java/com/example/talktoagent/NativeCProductionTest.java`
- `app/src/androidTest/java/com/example/talktoagent/ConnectionLifecycleUiTest.java`
- `app/src/androidTest/java/com/example/talktoagent/StitchVoiceHomeTest.java`

### 3. 補齊可自動驗證的長篇定稿

訊息：

```text
test(android): 驗證大字體長篇定稿的全文複製

避免把可重現的長文驗證留給真人，確認取消後保留的定稿在大字體下可捲動查看並完整複製，且不產生送出事件。
```

Add 清單：
- `app/src/androidTest/java/com/example/talktoagent/StitchVoiceHomeTest.java`

這是既有能力的追加回歸測試，不宣稱有新的產品 RED；最後完整原生套件增至 19 項，仍全數通過。

### 4. 本票狀態／交付證據

訊息：

```text
docs(stitch): 記錄首頁原生驗證與待真人驗收項目

保留模型、測試與截圖證據，區分自動通過與真實手機雲端驗收，並交代後兩票可沿用的局部呈現邊界。
```

Add 清單：
- `.scratch/stitch-ui/issues/01-voice-home.md`
- `.scratch/stitch-ui/reports/ticket-01.md`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/unavailable.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/ready.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/preparing.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/recording.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/finalizing.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/sending.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/completed.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/cancelled.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/failed.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/unknown.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/compact-320dp-font2-long.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/connection.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/diagnostics-draft.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/ready-gesture.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/design-comparison.png`
- `.scratch/stitch-ui/screenshots/ticket-01/verified/long-final-font2-copy.png`

### 排除清單

以下使用者既有無關改動保持未修改／未 stage：`docs/prototype/uiux/README.md`、`docs/prototype/uiux/prototype.css`、`docs/prototype/uiux/prototype.js`、`docs/issue-9-agent-brief.md`、`docs/spec-minimalist-ui.md`、`docs/spec-qr-connection-invitation.md`。後兩份僅讀，不為補齊文件連結而提交。

另排除本地 RED APK、Gradle 產物、原始 UTF-16 tee log、重複／過期截圖、CLI layout dump、commit 訊息檔及所有不在上述清單的 scratch 檔案，避免暫存產物混入 atomic commits。
