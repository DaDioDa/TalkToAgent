# 安全更新實作交付（未提交）

## 配置及範圍

- 第一個命令及收尾第二次核對：`PI_PROVIDER=openai-codex`、`PI_MODEL=gpt-6.1-sol`、`PI_REASONING_LEVEL=medium`。父方仍須核對完整 JSONL metadata。
- Windows PowerShell `5.1.26100.9444`、Python `3.14.6`。驗證時 `JAVA_HOME=D:/andriodStudio/jbr`，SDK `C:/Users/Ta.Hsiung/AppData/Local/Android/Sdk`，build-tools `36.0.0`；通用程式無這些使用者絕對路徑／手機 serial。
- HEAD 仍為 `4d6f5ff74ee512fd59323b170acb534f3cc36295`，原 branch；無 commit／push／stage／reset／clean，保留原 `.idea`／`.scratch/stitch-ui`。規格文件未修改。
- 未執行任何真 ADB／device CLI；未啟動 AVD，未操作實體手機。`android` 不在 PATH，找到 SDK cmdline-tools `15859902/bin/android.exe`，查 skills find install／skills help／install help，完整讀已安裝 android-cli SKILL.md；未修改全域 skills。

## Changed paths

- `tools/android/update.ps1`：單一 CLI，工具鏈解析及獨占暫存清理。
- `tools/android/SafeUpdate.psm1`：公開流程、native argv 邊界；唯一選擇、APK 身分／完整 signer 集合、版本／SDK、前後快照、替換安裝、安裝後核對、單次啟動。
- `tools/android/README.md`：命令及已接受的 profile／競態／資料邊界。
- `tests/test_android_update.py`
- `tests/android_update_harness.ps1`
- `tests/android_update_native.ps1`
- `tests/android_update_native_probe.py`
- `AGENTS.md`：一個條件導航。
- `docs/android-voice.md`：精簡更新入口導航；保留舊文，明確區別並存安裝。
- 本交付報告（不應與產品程式一起 stage）。

## RED／GREEN 與負向控制

- 第一 tracer：`python -m unittest discover -s tests -p test_android_update.py -v`，入口 module 尚不存在時 **1 FAIL**；實作公開流程後 **1 PASS**。
- 真 native argv 中文／空白路徑：起初 **1 FAIL**（fixture 使用 Windows 8.3 TEMP 前綴，與正式隔離路徑 canonical 化不一致），將 fixture 根路徑 resolve 後 **1 PASS**。完整流程經正式 native adapter 啟動真的 Python 外部程序，記錄逐項 argv；不是只觀察 mock 函數參數。
- 離線真 `aapt2` 發現新版使用 `minSdkVersion:`。先新增 `-k real_min_sdk`：**1 FAIL**；支援嚴格 numeric 的新版／舊版 SDK 標籤後 **1 PASS**。
- 安裝／啟動 native 非零退出的狀態訊息，各 **1 FAIL**；補已變更／已核對的階段訊息後各 **1 PASS**。
- 明確空 serial／package／null user 不能回退自動選擇：**1 FAIL**；補參數驗證後 **1 PASS**。
- 刻意停用新 APK 包名及 signer guard（僅暫時修改正式 guard，非新增 bypass flag）：
  - `python -m unittest discover -s tests -p test_android_update.py -k reject_wrong_package -v`：**1 FAIL**，觀察到 install=1、launch=0，證明事後核對無法取代安裝前 guard。
  - `python -m unittest discover -s tests -p test_android_update.py -k reject_signer_mismatch -v`：**1 FAIL**，install=1、launch=1。
  - 兩個 guard 均已還原；最後全套 GREEN 包含兩案。

## 最終重跑與結果

在 PowerShell 專案根目錄：

```powershell
$env:JAVA_HOME = 'D:/andriodStudio/jbr' # 此機驗證環境；其他機器填自己的 JDK
python -m unittest discover -s tests -v
python -m unittest discover -s windows_receiver -v
```

- `tests`：**78 PASS**（新安全更新 **76** ＋既有 Java/Python interop **2**），85.568 秒。
- `windows_receiver`：既有 **36 PASS**，18.849 秒。合計 **114 PASS**。
- 新測試內有四個 `.ps1/.psm1` 的 `System.Management.Automation.Language.Parser::ParseFile` 語法檢查，及 CLI 缺 SDK／缺工具／非法版本／未知 bypass 參數／無效 JAVA_HOME 拒絕測試。
- 原生程序退出 `7` 即使 stdout 顯示 Success 也停止建置後流程，未提出 install。
- logs：`.gradle/safe-update-evidence/final-tests.log`、`final-receiver.log`（已轉 UTF-8）；`git diff --check` 通過。

離線真 Gradle／APK 工具 smoke（本機 ignored harness，不呼叫真 ADB）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .gradle/safe-update-evidence/offline-smoke.ps1 -SdkRoot C:/Users/Ta.Hsiung/AppData/Local/Android/Sdk -JdkRoot D:/andriodStudio/jbr
```

兩次通過；正式 `Invoke-SafeAndroidUpdate -CheckOnly` 使用假 device 回應、真 Gradle／aapt2／apksigner。隔離建置／凍結 APK／前後重查完成，原 `app/build/outputs/apk/debug/app-debug.apk` hash 保持不變。最終產物 versionCode=1、minSDK=36、signer SHA-256=`f20e40e542c058018a8d9911b908c4f12aa47183e1a209b52bb816ac3bad0b90`、APK SHA-256=`D21D94782082BEDBA376E93859CF838651F9EB062A5851621AC96F63A1D24600`。Installed／Verified／Launched 均 false，**不代表實際安裝成功**。

## 父方注意

- 已自行重新讀完整程式並核對規格／scope；尚未做獨立 review，依派工由父方另派。本環境未提供 Agent 工具，不假稱已完成雙代理 code-review。
- OEM 的 pm／users／驗簽輸出、工作 profile 權限、真實安裝後 APK hash、啟動結果及資料 migration 未實機驗證；不支援／不明輸出安全拒絕。split APK、versionCodeMajor 及不同 signer 集合／輪替明確拒絕。
- 正常 success 測試主要走公開 module API；CLI 本身做工具鏈及未知參數拒絕測試。native argv 測試走正式協調＋正式 native adapter，工具外部程序是假工具。
- 此工具保留資料僅指不用 uninstall／clear；同包名 binary 跨 profile 共用，preflight/recheck 非原子 update-only。真 emulator 安裝 smoke 由父方在明確隔離的任務模擬器進行。
