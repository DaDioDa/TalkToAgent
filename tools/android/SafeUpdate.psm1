Set-StrictMode -Version Latest

# The only injectable boundary is an external process, never selection or guards.
function Invoke-UpdateNativeCommand {
    param([string]$File, [string[]]$Arguments)
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $File @Arguments 2>&1
        $code = $LASTEXITCODE
        [pscustomobject]@{ ExitCode = $code; Output = ($output | ForEach-Object { "$($_)" }) -join "`n" }
    } finally { $ErrorActionPreference = $saved }
}

function Invoke-SafeAndroidUpdate {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$ProjectRoot,
        [Parameter(Mandatory)][string]$Workspace,
        [Parameter(Mandatory)][hashtable]$Tools,
        [string]$Serial,
        [Nullable[int]]$UserId,
        [string]$Package,
        [switch]$CheckOnly,
        [scriptblock]$CommandRunner = { param($File, $Arguments) Invoke-UpdateNativeCommand $File $Arguments }
    )
    $ErrorActionPreference = 'Stop'
    $allowed = @('com.example.talktoagent', 'com.example.talktoagent.bluetoothdebug')
    foreach ($key in @('Serial', 'Package', 'UserId')) {
        if ($PSBoundParameters.ContainsKey($key) -and [string]::IsNullOrWhiteSpace([string]$PSBoundParameters[$key])) { throw "明確指定的 $key 不可為空；不會回退自動選擇。" }
    }
    if ($Package -and $Package -cnotin $allowed) { throw '不允許的完整套件名；請指定本專案正式包或 Bluetooth Debug 包。' }
    if ($Serial -and $Serial -notmatch '^[A-Za-z0-9._:\[\]-]+$') { throw '無法驗證裝置 serial。' }
    if ($null -ne $UserId -and $UserId -lt 0) { throw 'Android user ID 必須是非負整數。' }
    foreach ($key in @('Adb', 'Aapt', 'Signer', 'Gradle')) {
        if (-not $Tools.ContainsKey($key) -or -not $Tools[$key]) { throw "缺少工具：$key" }
    }
    $Workspace = [IO.Path]::GetFullPath($Workspace)
    if (@(Get-ChildItem -LiteralPath $Workspace -Force -ErrorAction SilentlyContinue).Count -gt 0) { throw '暫存工作目錄必須為空，避免使用舊產物。' }
    New-Item -ItemType Directory -Force -Path $Workspace | Out-Null
    function Run([string]$Tool, [string[]]$Argv) {
        $r = & $CommandRunner $Tools[$Tool] $Argv
        if ($null -eq $r -or $r.ExitCode -ne 0) { throw "外部命令失敗 ($Tool $($Argv -join ' '))：$($r.Output)" }
        return ([string]$r.Output).Trim()
    }
    function Adb([string]$Device, [string[]]$Argv) { Run 'Adb' (@('-s', $Device) + $Argv) }
    function Available([string]$Device) {
        if ((Adb $Device @('get-state')) -cne 'device') { throw "裝置 $Device 不可用或未授權。" }
    }
    function CurrentUser([string]$Device) {
        $value = Adb $Device @('shell', 'am', 'get-current-user')
        if ($value -notmatch '^\d+$') { throw '無法辨識目前 Android user；請檢查權限。' }
        return [int]$value
    }
    function ValidateUser([string]$Device, [int]$Id) {
        $text = Adb $Device @('shell', 'pm', 'list', 'users')
        $lines = @($text -split '\r?\n' | Where-Object { $_.Trim() -ne '' })
        if ($lines.Count -lt 2 -or $lines[0].Trim() -cne 'Users:') { throw '無法驗證 Android users；請檢查權限。' }
        $ids = @()
        foreach ($line in $lines[1..($lines.Count - 1)]) {
            if ($line -notmatch '^\s*UserInfo\{(\d+):[^\r\n]*:[0-9a-fA-F]+\}(?:\s+running)?\s*$') { throw '不支援的 users 查詢結果。' }
            $ids += [int]$Matches[1]
        }
        if ($Id -notin $ids) { throw "Android user $Id 不存在或不可讀取。" }
    }
    function InstalledPackages([string]$Device, [int]$Id) {
        $text = Adb $Device @('shell', 'pm', 'list', 'packages', '--user', "$Id")
        $names = @()
        foreach ($line in @($text -split '\r?\n' | Where-Object { $_ -ne '' })) {
            if ($line -notmatch '^package:([a-zA-Z0-9_.]+)$') { throw '套件查詢失敗或無權讀取；不能視為不存在。' }
            $names += $Matches[1]
        }
        return $names
    }
    function ApkInfo([string]$Path) {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'APK 產物不存在。' }
        $hash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
        $badging = Run 'Aapt' @('dump', 'badging', $Path)
        $packages = @([regex]::Matches($badging, "(?m)^package: name='([^']+)' versionCode='(\d+)'[^\r\n]*$"))
        $sdk = @([regex]::Matches($badging, "(?m)^(?:minSdkVersion|sdkVersion):'(\d+)'\r?$"))
        if ($packages.Count -ne 1 -or $sdk.Count -ne 1 -or $packages[0].Value -match "\bsplit=|versionCodeMajor=") { throw '不支援或無法解析 APK 包名／版本／SDK／split。' }
        $max = @([regex]::Matches($badging, "(?m)^maxSdkVersion:'(\d+)'\r?$"))
        if ($badging -match '(?m)^maxSdkVersion:' -and $max.Count -ne 1) { throw '不支援的 maxSDK。' }
        $signing = Run 'Signer' @('verify', '--verbose', '--print-certs', $Path)
        if ($signing -notmatch '(?m)^Verifies\r?$') { throw 'APK 驗簽未成功。' }
        $counts = @([regex]::Matches($signing, '(?m)^Number of signers: (\d+)\r?$'))
        $certs = @([regex]::Matches($signing, '(?m)^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F]{64})\r?$'))
        if ($counts.Count -ne 1 -or [int]$counts[0].Groups[1].Value -lt 1 -or $certs.Count -ne [int]$counts[0].Groups[1].Value) { throw '簽署者集合缺漏或無法驗證。' }
        $digests = @(); $numbers = @()
        foreach ($cert in $certs) { $numbers += [int]$cert.Groups[1].Value; $digests += $cert.Groups[2].Value.ToLowerInvariant() }
        if (@($numbers | Sort-Object -Unique).Count -ne $certs.Count -or @($digests | Sort-Object -Unique).Count -ne $certs.Count) { throw '簽署者資料重複或不支援。' }
        for ($i = 1; $i -le $certs.Count; $i++) { if ($i -notin $numbers) { throw '簽署者編號不完整。' } }
        if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -cne $hash) { throw 'APK 在檢查時變動。' }
        [pscustomobject]@{
            Package = $packages[0].Groups[1].Value; Version = [long]$packages[0].Groups[2].Value
            MinSdk = [int]$sdk[0].Groups[1].Value; MaxSdk = $(if ($max.Count) { [int]$max[0].Groups[1].Value } else { [int]::MaxValue })
            Signers = ($digests | Sort-Object) -join ','; Hash = $hash
        }
    }
    function Snapshot([string]$Device, [int]$Id, [string]$Name, [string]$Label) {
        ValidateUser $Device $Id
        if ($Name -cnotin @(InstalledPackages $Device $Id)) { throw '選定的既有套件不存在／消失；停止，不能改選目標。' }
        $paths = Adb $Device @('shell', 'pm', 'path', '--user', "$Id", $Name)
        $lines = @($paths -split '\r?\n')
        if ($lines.Count -ne 1 -or $lines[0] -notmatch '^package:(/[^\r\n]+\.apk)$') { throw '無法取得單一既有 APK；split APK 或不明結果不支援。' }
        $remote = $Matches[1]
        $local = Join-Path $Workspace "$Label.apk"
        Adb $Device @('pull', $remote, $local) | Out-Null
        $info = ApkInfo $local
        if ($info.Package -cne $Name) { throw '既有 APK 實際包名不符目標。' }
        [pscustomobject]@{ Path = $remote; Info = $info }
    }
    $deviceText = Run 'Adb' @('devices', '-l')
    $deviceLines = @($deviceText -split '\r?\n' | Where-Object { $_.Trim() -ne '' })
    if (-not $deviceLines.Count -or $deviceLines[0] -cne 'List of devices attached') { throw '無法解析裝置清單。' }
    $devices = @()
    foreach ($line in @($deviceLines | Select-Object -Skip 1)) {
        if ($line -notmatch '^([A-Za-z0-9._:\[\]-]+)\s+(\S+)(?:\s+.*)?$') { throw '無法解析裝置狀態。' }
        if ($Serial -and $Matches[1] -cne $Serial) { continue }
        if ($Matches[2] -cne 'device') { throw "裝置 $($Matches[1]) 不可用／未授權；請明確指定可用 serial。" }
        $devices += $Matches[1]
    }
    if (-not $devices.Count) { throw '指定裝置不存在或沒有可用裝置。' }
    $candidates = @()
    foreach ($device in $devices) {
        Available $device
        $id = if ($null -ne $UserId) { [int]$UserId } else { CurrentUser $device }
        ValidateUser $device $id
        $installed = @(InstalledPackages $device $id)
        foreach ($name in $allowed) {
            if ((!$Package -or $Package -ceq $name) -and $name -cin $installed) {
                $candidates += [pscustomobject]@{ Serial = $device; User = $id; Package = $name }
            }
        }
    }
    if ($candidates.Count -ne 1) { throw "找到 $($candidates.Count) 個候選；請以 -Serial、-UserId、-Package 明確指定既有目標，不會首次安裝。" }
    $target = $candidates[0]
    $device = $target.Serial; $id = $target.User; $name = $target.Package
    Write-Information "選定既有目標：$device / user $id / $name；同包名程式碼跨 user/profile 共用，並非原子 update-only。" -InformationAction Continue
    $old = Snapshot $device $id $name 'before'
    Write-Information "既有 APK 快照已驗證：versionCode $($old.Info.Version)，SHA-256 $($old.Info.Hash)。" -InformationAction Continue
    $sdkText = Adb $device @('shell', 'getprop', 'ro.build.version.sdk')
    if ($sdkText -notmatch '^\d+$') { throw '無法確認裝置 SDK。' }
    $sdk = [int]$sdkText
    $buildRoot = Join-Path $Workspace 'build'
    $init = Join-Path $Workspace 'isolate.init.gradle'
    $escaped = $buildRoot.Replace('\', '/').Replace("'", "\'")
    [IO.File]::WriteAllText($init, "gradle.beforeProject { p -> p.layout.buildDirectory.set(new File('$escaped', p == p.rootProject ? 'root' : p.path.substring(1).replace(':', '/'))) }", [Text.UTF8Encoding]::new($false))
    $flag = if ($name -ceq $allowed[1]) { 'true' } else { 'false' }
    Push-Location $ProjectRoot
    try { Run 'Gradle' @('--init-script', $init, "-PbluetoothTestApp=$flag", ':app:assembleDebug', '--no-configuration-cache', '--console=plain') | Out-Null }
    finally { Pop-Location }
    $artifacts = @(Get-ChildItem -LiteralPath (Join-Path $buildRoot 'app/outputs/apk/debug') -Filter '*.apk' -File -ErrorAction SilentlyContinue)
    if ($artifacts.Count -ne 1) { throw '建置沒有產生唯一 APK；不支援拆分產物。' }
    $apk = Join-Path $Workspace '更新 APK.apk'
    Copy-Item -LiteralPath $artifacts[0].FullName -Destination $apk
    $new = ApkInfo $apk
    if ($new.Package -cne $name) { throw '新 APK 實際包名不符目標。' }
    if ($new.Signers -cne $old.Info.Signers) { throw '新舊 APK 完整簽署者集合不同；不支援簽章輪替。' }
    if ($new.Version -lt $old.Info.Version) { throw '拒絕降低 versionCode。' }
    if ($sdk -lt $new.MinSdk -or $sdk -gt $new.MaxSdk) { throw '裝置 SDK 不符合新 APK 要求。' }
    Write-Information "新產物已驗證：$($new.Package)，versionCode $($new.Version)，signers $($new.Signers)，SHA-256 $($new.Hash)。" -InformationAction Continue
    Available $device
    if ($null -eq $UserId -and (CurrentUser $device) -ne $id) { throw '自動選定的目前 user 已切換；停止。' }
    if ((Adb $device @('shell', 'getprop', 'ro.build.version.sdk')) -cne $sdkText) { throw '裝置 SDK 快照變動。' }
    $recheck = Snapshot $device $id $name 'recheck'
    if ($recheck.Path -cne $old.Path -or $recheck.Info.Hash -cne $old.Info.Hash -or ($recheck.Info | ConvertTo-Json -Compress) -cne ($old.Info | ConvertTo-Json -Compress)) { throw '既有 APK／身分快照變動；停止。' }
    if ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -cne $new.Hash) { throw '待安裝產物被替換；停止。' }
    $report = [ordered]@{ Target = $target; Artifact = $new; Checked = $true; Installed = $false; Verified = $false; Launched = $false; SharedCode = '同包名程式碼跨 user/profile 共用；--user 不是 binary 隔離。'; Race = '非原子 update-only；檢查後仍有短暫競態。' }
    Write-Information '安裝前目標／產物重新核對完成。' -InformationAction Continue
    if ($CheckOnly) { return [pscustomobject]$report }
    Write-Information "$($report.SharedCode) $($report.Race) 目標：$device / user $id / $name；不卸載／清資料。" -InformationAction Continue
    try { $installResult = Adb $device @('install', '--user', "$id", '-r', $apk) }
    catch { throw "安裝命令失敗；可能已變更，停止且不重試／啟動：$($_.Exception.Message)" }
    if ($installResult -notmatch '(?m)^Success\r?$' -or $installResult -match '(?i)failure|error') { throw '安裝結果不明或失敗；可能已變更，停止且不重試／啟動。' }
    $report.Installed = $true
    try {
        Available $device
        $after = Snapshot $device $id $name 'after'
        if (($after.Info | ConvertTo-Json -Compress) -cne ($new | ConvertTo-Json -Compress)) { throw '安裝後 APK hash／身分不符合本次產物。' }
    } catch { throw "安裝命令已成功，但核對失敗；裝置可能已更新，不回滾、不啟動：$($_.Exception.Message)" }
    $report.Verified = $true
    try { $launch = Adb $device @('shell', 'am', 'start', '--user', "$id", '-W', '-n', "$name/com.example.talktoagent.MainActivity") }
    catch { throw "已更新且核對成功，但啟動命令失敗；不重裝、不重試：$($_.Exception.Message)" }
    if ($launch -notmatch '(?m)^Status: ok\r?$' -or $launch -match '(?i)error|exception|warning') { throw '已更新且核對成功，啟動失敗或結果不明；不重裝、不重試，不保證前景。' }
    $report.Launched = $true
    return [pscustomobject]$report
}
Export-ModuleMember -Function Invoke-SafeAndroidUpdate, Invoke-UpdateNativeCommand
