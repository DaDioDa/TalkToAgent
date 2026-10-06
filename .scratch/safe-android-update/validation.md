# Android 安全更新：驗證交付

已實作並完成獨立審查與隔離模擬器驗證；**未操作使用者手機**。驗證基準為 `4d6f5ff74ee512fd59323b170acb534f3cc36295`；當時以未 stage／commit／push 的工作樹變更進行驗證，提交及推送另依使用者後續授權執行。

- [已確認規格](../../docs/spec-safe-android-update.md)
- [使用入口](../../tools/android/README.md)
- [實作者紀錄](implementation.md)
- [模型稽核](model-audit.json)

## 指揮官獨立重跑

設定本機 `JAVA_HOME` 後執行：

```powershell
python -m unittest discover -s tests -v
python -m unittest discover -s windows_receiver -v
```

- `tests`：**78／78**，65.685 秒；安全更新 76、既有 interop 2。
- `windows_receiver`：**36／36**，19.047 秒。
- 合計 **114 項通過**；沒有把模擬器 smoke 或重複執行再加成不同測試數。
- 記錄：[tests](../../.gradle/safe-update-evidence/commander-tests.log)、[Receiver](../../.gradle/safe-update-evidence/commander-receiver.log)。原生程序的 stderr 在 Windows PowerShell Tee 顯示為 NativeCommandError 物件；兩個程序實際退出碼都是 0，unittest 結尾均為 OK。
- 實作者另完成包名／signer guard 刻意切斷時的 RED，恢復後全套 GREEN；本節是父方另外重跑的正常版本，不冒稱父方重新執行過 mutation。

## 真實 API36 模擬器 smoke

僅使用本任務的 `TalkToAgentStitchApi36`／`emulator-5554`，所有工具呼叫明確指定此 serial；未對實體手機發出套件查詢、安裝或啟動命令。

1. **正式包預覽**：`-Serial emulator-5554 -CheckOnly` 經真 ADB、Gradle、aapt2、apksigner 成功。Installed／Verified／Launched 均 false；原 lastUpdateTime 仍為 `2026-10-05 12:19:17`，測試資料標記保留。
2. **正式包更新**：`-Serial emulator-5554` 成功更新、讀回核對及啟動。lastUpdateTime 改變；firstInstallTime 仍為 `2026-10-05 10:04:01`，CE／DE inode 仍為 `557098`／`401442`，專為本 smoke 建立的私有 files 標記仍存在；App 確實在模擬器前景。
3. **多候選拒絕**：測試 setup 在此模擬器新增 Bluetooth Debug fixture，使兩個合法包並存。不指定 Package 的呼叫退出 1，回報兩候選，沒有改變安裝快照。
4. **Bluetooth Debug 更新**：明確指定 `-Package com.example.talktoagent.bluetoothdebug`，成功依對應 flag 建置、更新、核對並啟動。firstInstallTime 仍為 `2026-10-06 03:16:00`，CE／DE inode 仍為 `557336`／`401567`，測試資料標記保留。

真 smoke 驗證的是同 versionCode 的兩個既有安裝；其他版本／拒絕分支由受控外部命令測試覆蓋，不宣稱每種 OEM 行為都經實機測過。

APK hash：

- 正式包：`D21D94782082BEDBA376E93859CF838651F9EB062A5851621AC96F63A1D24600`
- Bluetooth Debug：`04EF5550ECF8D2F54648AA4C9B17375E1FBCA043AC80316E68C586C31322F0ED`

原始記錄：[預覽](../../.gradle/safe-update-evidence/commander-preview-remotesigned.log)、[正式更新](../../.gradle/safe-update-evidence/commander-install.log)、[歧義拒絕](../../.gradle/safe-update-evidence/commander-ambiguous.log)、[BT 更新前](../../.gradle/safe-update-evidence/commander-bluetooth-before.txt)、[BT 更新](../../.gradle/safe-update-evidence/commander-bluetooth-install.log)。安裝後 inode／標記／前景核對亦保存在本次指揮官 session 原始工具結果。

不帶執行原則參數的初次 CLI 嘗試被本機 Windows 預設 Restricted 拒絕，未進入更新程式。後續使用**僅該次程序**的 `-ExecutionPolicy RemoteSigned` 通過；未修改使用者、機器或群組原則。使用文件以此較窄設定取代 Bypass；組織原則仍阻擋時應停止。

結束已移除本 smoke 自行建立的標記及 BT fixture，停止模擬器；沒有卸載原本的正式包。這是測試 setup／cleanup，安全更新工具本身沒有 uninstall／clear 路徑。原 `app/build/outputs/apk/debug/app-debug.apk` hash 保持不變。

## Standards

獨立代理 `31518f3d-860a-40b`：**APPROVED**。已讀 tracked diff 與新檔，未發現具體 repo 規範違反、新安全性 bug 或需提出的 Fowler smell。確認安全協調集中於正式 module、測試只替換外部命令、沒有卸載／清資料／降版／自動重試路徑。

## Spec

獨立代理 `2652e367-0851-4fe`：**APPROVED**。未發現缺漏、錯誤實作或 scope creep；公開更新流程與測試邊界、shared-code、非原子競態及不支援情況的拒絕均符合確認規格。

兩位 reviewer 均唯讀，沒有執行 runtime。以上 runtime 結果來自指揮官的另行驗證，不以静態 review 取代。複查後只更新交付狀態／證據及使用文件的 process-scoped 執行原則示例，程式和測試未再修改。

## 模型與限制

已核對調查、實作及兩位 reviewer 共四個代理的**完整 assistant 執行 metadata**，全部為 `openai-codex/gpt-6.1-sol`、`medium`；調查代理的續跑也包含在稽核內，不只信任派工參數或環境自述。

尚未驗證真 Samsung／工作設定檔／安全資料夾的 OEM 輸出。相同包名 code 跨 profile 共用，重新檢查不提供原子 update-only；這些是已同意且寫入文件的限制。保留資料證據僅代表更新未清除既有安裝與合成標記，不等同證明所有 App migration 或真實金鑰／授權流程。
