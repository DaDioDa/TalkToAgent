# 本專案 Android 安全更新

更新**已存在**的正式包或 Bluetooth Debug 包；不提供首次／並存安裝、任意外來 APK 或簽章輪替。完整需求見 [規格](../../docs/spec-safe-android-update.md)。

從專案根目錄執行 Windows PowerShell：

```powershell
# 先檢查：可建置／讀取 APK，但不安裝、不啟動、不授權
powershell -NoProfile -ExecutionPolicy RemoteSigned -File tools/android/update.ps1 -CheckOnly

# 只有唯一既有目標才會自動選定；通常使用目前 Android user
powershell -NoProfile -ExecutionPolicy RemoteSigned -File tools/android/update.ps1

# 有歧義時明確指定既有目標；實際 serial／user 由操作者填入
powershell -NoProfile -ExecutionPolicy RemoteSigned -File tools/android/update.ps1 -Serial SERIAL -UserId 10 -Package com.example.talktoagent.bluetoothdebug -CheckOnly
```

上述執行原則只套用該次 PowerShell 程序，不修改使用者／機器設定；若組織原則仍阻擋，停止並交由管理者處理。

`-SdkRoot` 可指定已安裝 SDK；否則依序讀 `ANDROID_HOME`、`ANDROID_SDK_ROOT`、本專案 `local.properties`。`-BuildToolsVersion` 可指定已安裝的穩定版，預設選最高穩定版。需要 JDK（`JAVA_HOME` 或 `PATH`）、SDK 的 `adb.exe`、`aapt2.exe`、`apksigner.bat` 及專案 Gradle wrapper。不自動安裝工具。使用真正的 ADB argv 執行 `install --user USER -r APK`；Android CLI help 未定義多 install-options 的分隔規則，故不用猜測的字串組合。

## 保護與結果

- 精確選定 serial／user／完整套件名，讀取既有單一 APK，依目標明確建置 `bluetoothTestApp=false` 或 `true`。診斷包、`.test`、部分名稱不會當成候選；查詢／權限失敗不會當成未安裝。
- 以 APK 實際包名、驗簽成功的**完整 signer 集合**、versionCode、SDK、SHA-256 判定。不取第一枚 signer、不把 source stamp 當 signer；不同集合、降版、split APK／不支援的 metadata 均停止，同 versionCode 可更新。
- 每次建置／凍結產物位於 `.gradle/safe-update/<隨機識別>/`，不覆蓋 `app/build` 的交付 APK，不改 Gradle 受版控設定。結束僅移除該次暫存目錄。
- 安裝前重新查目標及凍結產物；更新後讀回實際 APK 核對 hash／metadata，才啟動已知 `com.example.talktoagent.MainActivity` 一次。命令失敗、結果不明、核對失敗或啟動失敗不重試、不卸載、不清資料、不補授權。
- 成功報告包含目標、版本、完整 signer 集合、hash、檢查／安裝／核對／啟動狀態。`-CheckOnly`／檢查預覽**不等於已安裝**。成功啟動命令也不保證 App 留在前景。失敗退出碼為 `1`，成功為 `0`。

## 已接受的邊界

同包名程式碼跨 user／工作 profile 共用：`--user` **不是 binary 隔離**；其他已安裝同包名的 profile 也會使用新版，各 user 資料仍分離。不能把無權查閱的 profile 宣稱為沒有安裝。

此工具不是原子 update-only；重查只拒絕已觀察的變動，檢查後仍有極短競態。操作期間避免其他安裝／卸載／user 切換。保留資料是不用卸載／清資料等破壞手段，不保證新 App 自己的 migration。安裝後核對失敗不能宣稱已回滾。

## 不連装置的測試

```powershell
python -m unittest discover -s tests -p test_android_update.py -v
```

測試呼叫公開 `Invoke-SafeAndroidUpdate`，只替換外部程序邊界；選擇、守衛及協調使用正式邏輯。native argv 案例執行真的 Python 子程序，驗證中文／空白路徑的逐項參數；其工具替身會拒絕意外命令，完全不使用真 ADB。開發者不要以使用者手機做 smoke；實際安裝須另行選明確隔離的任務模擬器。

`SafeUpdate.psm1` 的公開 API 供測試及本入口呼叫；嵌入者須提供空的獨占 `Workspace`，並負責只清理該目錄。外部 runner 的契約是 `(File, string[] Arguments) -> { ExitCode, Output }`，不是略過安全檢查的 CLI 選項。
