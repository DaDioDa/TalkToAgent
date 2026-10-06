param([string]$Scenario, [string]$Workspace, [string]$OptionsJson = '{}')
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$options = @{}
(ConvertFrom-Json $OptionsJson).PSObject.Properties | ForEach-Object { $options[$_.Name] = $_.Value }
$user = '10'; if ($options.ContainsKey('UserId')) { $user = [string]$options.UserId }
$state = @{ Installs = 0; Launches = 0; Build = @(); Install = @(); Paths = 0; Currents = 0; Gets = 0; SdkGets = 0; Commands = @() }
$package = 'com.example.talktoagent'
if ($Scenario -eq 'bluetooth') { $package += '.bluetoothdebug' }
$runner = {
    param($File, [string[]]$Arguments)
    $state.Commands += ,@($File, $Arguments)
    $text = ($Arguments -join ' ').Replace('-s fake-other', '-s fake-serial')
    $out = ''; $code = 0
    switch ([IO.Path]::GetFileName($File)) {
        'adb.exe' {
            if ($text -eq 'devices -l') { $out = "List of devices attached`nfake-serial device product:fake" }
            elseif ($text -eq '-s fake-serial get-state') { $out = 'device'; $state.Gets++ }
            elseif ($text -eq '-s fake-serial shell am get-current-user') { $out = '10'; $state.Currents++ }
            elseif ($text -eq '-s fake-serial shell pm list users') { $out = "Users:`n`tUserInfo{0:Owner:13} running`n`tUserInfo{10:Work:30} running" }
            elseif ($text -eq "-s fake-serial shell pm list packages --user $user") { $out = "package:$package`npackage:com.example.talktoagent.qrdiagnosis`npackage:com.example.talktoagent.test" }
            elseif ($text -eq '-s fake-serial shell getprop ro.build.version.sdk') { $out = '36'; $state.SdkGets++ }
            elseif ($text -eq "-s fake-serial shell pm path --user $user $package") { $state.Paths++; $out = 'package:/data/app/fake/base.apk' }
            elseif ($Arguments[2] -eq 'pull') {
                $content = 'old'; if ($state.Installs -gt 0) { $content = 'new' }
                [IO.File]::WriteAllText($Arguments[4], $content); $out = '1 file pulled'
            }
            elseif ($Arguments[2] -eq 'install') { $state.Installs++; $state.Install = $Arguments; $out = 'Success' }
            elseif ($text -eq "-s fake-serial shell am start --user $user -W -n $package/com.example.talktoagent.MainActivity") { $state.Launches++; $out = "Status: ok`nActivity: $package/com.example.talktoagent.MainActivity" }
            else { throw "Unexpected fake ADB command: $text" }
        }
        'gradlew.bat' {
            $state.Build = $Arguments
            $outDir = Join-Path $Workspace 'build/app/outputs/apk/debug'
            New-Item -ItemType Directory -Force $outDir | Out-Null
            [IO.File]::WriteAllText((Join-Path $outDir 'app-debug.apk'), 'new')
        }
        'aapt2.exe' { $out = "package: name='$package' versionCode='1' versionName='1.0'`nsdkVersion:'36'`nlaunchable-activity: name='com.example.talktoagent.MainActivity'" }
        'apksigner.bat' { $out = "Verifies`nNumber of signers: 1`nSigner #1 certificate SHA-256 digest: $('a' * 64)" }
        default { throw "Unexpected external command: $File $text" }
    }
    # Scenario overrides affect only external command responses/files.
    if ($File -eq 'adb.exe') {
        if ($text -eq 'devices -l') {
            switch ($Scenario) {
                'offline' { $out = "List of devices attached`nfake-serial offline" }
                'unauthorized' { $out = "List of devices attached`nfake-serial unauthorized" }
                'device-query-fail' { $code = 1; $out = 'daemon error' }
                'no-devices' { $out = 'List of devices attached' }
                'two-devices' { $out += "`nfake-other device" }
            }
        }
        if ($text -like '*get-current-user') {
            if ($Scenario -eq 'current-unknown') { $out = 'unknown' }
            if ($Scenario -eq 'current-switch' -and $state.Currents -gt 1) { $out = '0' }
        }
        if ($text -like '*pm list users') {
            if ($Scenario -eq 'user-denied') { $out = 'SecurityException: denied' }
            if ($Scenario -eq 'user-query-fail') { $code = 1 }
            if ($Scenario -eq 'user-absent') { $out = "Users:`nUserInfo{0:Owner:13} running" }
        }
        if ($text -like '*pm list packages*') {
            if ($Scenario -in @('absent', 'only-diagnosis', 'substring')) { $out = 'package:com.example.talktoagent.qrdiagnosis' }
            if ($Scenario -eq 'substring') { $out += "`npackage:com.example.talktoagent.extra" }
            if ($Scenario -eq 'two-packages') { $out += "`npackage:com.example.talktoagent.bluetoothdebug" }
            if ($Scenario -eq 'package-query-fail') { $code = 1 }
            if ($Scenario -eq 'package-denied') { $out = 'Error: permission denied' }
            if ($Scenario -eq 'target-disappears' -and $state.Paths -gt 0) { $out = '' }
        }
        if ($text -like '*get-state') {
            if ($Scenario -eq 'state-error') { $code = 1 }
            if ($Scenario -eq 'state-drift' -and $state.Gets -gt 1) { $out = 'offline' }
            if ($Scenario -eq 'artifact-drift' -and $state.Gets -gt 1) {
                $frozen = Get-ChildItem -LiteralPath $Workspace -Filter '*.apk' | Where-Object { $_.Name -ne 'before.apk' } | Select-Object -First 1
                [IO.File]::WriteAllText($frozen.FullName, 'tampered')
            }
        }
        if ($text -like '*getprop*') {
            if ($Scenario -eq 'sdk-low') { $out = '35' }
            if ($Scenario -eq 'sdk-unknown') { $out = 'preview' }
            if ($Scenario -eq 'sdk-drift' -and $state.SdkGets -gt 1) { $out = '37' }
        }
        if ($text -like '*pm path*') {
            if ($Scenario -eq 'path-error') { $code = 1 }
            if ($Scenario -eq 'split') { $out += "`npackage:/data/app/fake/split.apk" }
            if ($Scenario -eq 'path-drift' -and $state.Paths -gt 1) { $out = 'package:/data/app/changed/base.apk' }
        }
        if ($Arguments[2] -eq 'pull') {
            if ($Scenario -eq 'pull-error') { $code = 1 }
            if ($Scenario -eq 'old-drift' -and $state.Paths -gt 1 -and $state.Installs -eq 0) { [IO.File]::WriteAllText($Arguments[4], 'changed') }
            if ($Scenario -eq 'after-hash' -and $state.Installs -gt 0) { [IO.File]::WriteAllText($Arguments[4], 'unexpected') }
            if ($Scenario -eq 'after-query-fail' -and $state.Installs -gt 0) { $code = 1 }
        }
        if ($Arguments[2] -eq 'install') {
            if ($Scenario -eq 'install-error') { $code = 1; $out = 'Failure [INSTALL_FAILED]' }
            if ($Scenario -eq 'install-unknown') { $out = 'stream completed' }
        }
        if ($text -like '*am start*') {
            if ($Scenario -eq 'launch-error') { $code = 1; $out = 'Error' }
            if ($Scenario -eq 'launch-unknown') { $out = 'Starting: Intent' }
        }
    }
    if ($File -eq 'gradlew.bat') {
        if ($Scenario -eq 'build-error') { $code = 1 }
        if ($Scenario -eq 'missing-artifact') { Remove-Item -LiteralPath (Join-Path $outDir 'app-debug.apk') }
        if ($Scenario -eq 'multiple-artifacts') { [IO.File]::WriteAllText((Join-Path $outDir 'other.apk'), 'other') }
    }
    if ($File -eq 'aapt2.exe') {
        $isOld = [IO.Path]::GetFileName($Arguments[2]) -in @('before.apk', 'recheck.apk')
        $isAfter = [IO.Path]::GetFileName($Arguments[2]) -eq 'after.apk'
        if (($Scenario -eq 'wrong-package' -and -not $isOld) -or ($Scenario -eq 'old-wrong-package' -and $isOld) -or ($Scenario -eq 'after-package' -and $isAfter)) { $out = $out.Replace($package, 'com.example.other') }
        if ($Scenario -eq 'downgrade' -and $isOld) { $out = $out.Replace("versionCode='1'", "versionCode='2'") }
        if ($Scenario -eq 'upgrade' -and -not $isOld) { $out = $out.Replace("versionCode='1'", "versionCode='2'") }
        if ($Scenario -eq 'real-min-sdk') { $out = $out.Replace('sdkVersion:', 'minSdkVersion:') }
        if ($Scenario -eq 'old-metadata-drift' -and [IO.Path]::GetFileName($Arguments[2]) -eq 'recheck.apk') { $out = $out.Replace("versionCode='1'", "versionCode='2'") }
        if ($Scenario -eq 'max-sdk-low') { $out += "`nmaxSdkVersion:'35'" }
        if ($Scenario -eq 'sdk-preview') { $out = $out.Replace("sdkVersion:'36'", "sdkVersion:'Preview'") }
        if ($Scenario -eq 'bad-metadata') { $out = 'unparseable' }
        if ($Scenario -eq 'metadata-error') { $code = 1 }
        if ($Scenario -eq 'apk-split') { $out = $out.Replace("versionName='1.0'", "versionName='1.0' split='feature'") }
    }
    if ($File -eq 'apksigner.bat') {
        $isOld = [IO.Path]::GetFileName($Arguments[3]) -in @('before.apk', 'recheck.apk')
        if ($Scenario -eq 'apk-read-drift') { [IO.File]::WriteAllText($Arguments[3], 'tampered while reading') }
        if ($Scenario -eq 'valid-source-stamp') { $out += "`nSource Stamp Signer certificate SHA-256 digest: $('b' * 64)" }
        if ($Scenario -eq 'duplicate-signer') { $out += "`nSigner #1 certificate SHA-256 digest: $('a' * 64)" }
        if ($Scenario -eq 'signer-mismatch' -and -not $isOld) { $out = $out.Replace(('a' * 64), ('b' * 64)) }
        if ($Scenario -eq 'signer-error') { $code = 1 }
        if ($Scenario -eq 'old-signer-error' -and $isOld) { $code = 1 }
        if ($Scenario -eq 'signer-unverified') { $out = $out.Replace('Verifies', 'DOES NOT VERIFY') }
        if ($Scenario -eq 'signer-missing') { $out = $out.Replace('Number of signers: 1', 'Number of signers: 2') }
        if ($Scenario -eq 'source-stamp') { $out = "Verifies`nNumber of signers: 1`nSource Stamp Signer certificate SHA-256 digest: $('a' * 64)" }
        if ($Scenario -in @('multi-match', 'multi-partial', 'multi-order', 'multi-extra')) {
            if ($isOld -or $Scenario -in @('multi-match', 'multi-order', 'multi-extra')) {
                $out = "Verifies`nNumber of signers: 2`nSigner #1 certificate SHA-256 digest: $('a' * 64)`nSigner #2 certificate SHA-256 digest: $('b' * 64)"
            }
            if ($Scenario -eq 'multi-order' -and -not $isOld) { $out = "Verifies`nNumber of signers: 2`nSigner #1 certificate SHA-256 digest: $('b' * 64)`nSigner #2 certificate SHA-256 digest: $('a' * 64)" }
            if ($Scenario -eq 'multi-extra' -and -not $isOld) { $out += "`nSigner #3 certificate SHA-256 digest: $('c' * 64)"; $out = $out.Replace('signers: 2', 'signers: 3') }
        }
    }
    [pscustomobject]@{ ExitCode = $code; Output = $out }
}.GetNewClosure()
try {
    Import-Module "$PSScriptRoot/../tools/android/SafeUpdate.psm1" -Force
    $tools = @{ Adb = 'adb.exe'; Aapt = 'aapt2.exe'; Signer = 'apksigner.bat'; Gradle = 'gradlew.bat' }
    $result = Invoke-SafeAndroidUpdate -ProjectRoot (Resolve-Path "$PSScriptRoot/..").Path -Workspace $Workspace -Tools $tools -CommandRunner $runner @options
    $ok = $true; $errorText = ''; $report = $result
} catch { $ok = $false; $errorText = $_.Exception.Message; $report = $null }
@{ ok = $ok; error = $errorText; report = $report; installs = $state.Installs; launches = $state.Launches; build = @($state.Build); install = @($state.Install) } | ConvertTo-Json -Depth 8 -Compress
