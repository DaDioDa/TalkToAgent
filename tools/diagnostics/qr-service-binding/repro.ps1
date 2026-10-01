$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$env:JAVA_HOME = 'C:/Users/Ta.Hsiung/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2'
$env:ANDROID_HOME = 'C:/Users/Ta.Hsiung/AppData/Local/Android/Sdk'
$root = (Resolve-Path "$PSScriptRoot/../../..").Path
$adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
$aapt = "$env:ANDROID_HOME/build-tools/36.0.0/aapt2.exe"
$serial = 'R5CX71D1SKD'
$app = 'com.example.talktoagent.qrdiagnosis'
$test = "$app.test"
$deviceState = & $adb -s $serial get-state 2>&1
if ($LASTEXITCODE -ne 0 -or $deviceState -notcontains 'device') {
    throw "Device $serial unavailable; reconnect/unlock and authorize USB debugging before running this diagnostic"
}
Push-Location $root
try {
    & ./gradlew.bat --init-script "$PSScriptRoot/isolation.init.gradle" :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Diagnostic APK build failed' }
    $appApk = "$root/app/build/outputs/apk/debug/app-debug.apk"
    $testApk = "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    foreach ($pair in @(@($appApk, $app), @($testApk, $test))) {
        $badging = & $aapt dump badging $pair[0]
        if ($LASTEXITCODE -ne 0 -or -not ($badging -match "^package: name='$($pair[1])' ")) {
            throw 'Isolation check failed; refusing to install any APK'
        }
    }
    foreach ($apk in @($appApk, $testApk)) {
        & $adb -s $serial install -r -t $apk
        if ($LASTEXITCODE -ne 0) { throw 'Isolated install failed' }
    }
    foreach ($permission in @('android.permission.BLUETOOTH_CONNECT', 'android.permission.POST_NOTIFICATIONS')) {
        & $adb -s $serial shell pm grant $app $permission
        if ($LASTEXITCODE -ne 0) { throw 'Isolated permission grant failed' }
    }
    & $adb -s $serial shell am force-stop $app
    $output = & $adb -s $serial shell am instrument -w -r -e class com.example.talktoagent.QrServiceBindingDiagnosticTest "$test/androidx.test.runner.AndroidJUnitRunner" 2>&1
    $output | ForEach-Object { Write-Output $_ }
    if ($output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|Process crashed') { $verdict = 1 }
    elseif ($output -match 'OK \(1 test\)') { $verdict = 0 }
    else { throw 'No valid diagnostic test verdict' }
} finally {
    # No uninstall/clear/reads of the user's original package. Restore its foreground only.
    & $adb -s $serial shell am force-stop $app | Out-Null
    & $adb -s $serial shell am start -n com.example.talktoagent.bluetoothdebug/com.example.talktoagent.MainActivity | Out-Null
    Pop-Location
}
exit $verdict
