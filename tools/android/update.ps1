[CmdletBinding()]
param(
    [string]$Serial,
    [Nullable[int]]$UserId,
    [string]$Package,
    [switch]$CheckOnly,
    [string]$SdkRoot,
    [string]$BuildToolsVersion
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$workspace = $null
try {
    Import-Module (Join-Path $PSScriptRoot 'SafeUpdate.psm1') -Force
    if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
    if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
    if (-not $SdkRoot -and (Test-Path -LiteralPath "$root/local.properties")) {
        $sdkLine = @(Get-Content -LiteralPath "$root/local.properties" | Where-Object { $_ -match '^sdk\.dir=' })
        if ($sdkLine.Count -eq 1) { $SdkRoot = $sdkLine[0].Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
    if (-not $SdkRoot -or -not (Test-Path -LiteralPath $SdkRoot -PathType Container)) { throw '找不到 Android SDK；請指定 -SdkRoot 或 ANDROID_HOME。' }
    if ($BuildToolsVersion -and $BuildToolsVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'BuildToolsVersion 必須為已安裝的穩定版本號。' }
    if (-not $BuildToolsVersion) {
        $versions = @(Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Directory | Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending)
        if (-not $versions.Count) { throw '找不到已安裝的 build-tools；請以 android sdk 安裝。' }
        $BuildToolsVersion = $versions[0].Name
    }
    $buildTools = Join-Path $SdkRoot "build-tools/$BuildToolsVersion"
    $tools = @{ Adb = (Join-Path $SdkRoot 'platform-tools/adb.exe'); Aapt = (Join-Path $buildTools 'aapt2.exe'); Signer = (Join-Path $buildTools 'apksigner.bat'); Gradle = (Join-Path $root 'gradlew.bat') }
    foreach ($tool in $tools.Values) { if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "找不到必要工具：$tool" } }
    if ($env:JAVA_HOME) {
        if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { throw 'JAVA_HOME 無效；請設定有效 JDK。' }
    } elseif (-not (Get-Command java.exe -ErrorAction SilentlyContinue)) { throw '找不到 Java；請設定 JAVA_HOME 或 PATH。' }
    # Only this invocation's directory is removed, never a shared build output.
    $workspace = Join-Path $root ('.gradle/safe-update/' + [guid]::NewGuid().ToString('N'))
    $options = @{ ProjectRoot = $root; Workspace = $workspace; Tools = $tools; CheckOnly = $CheckOnly }
    foreach ($key in @('Serial', 'UserId', 'Package')) { if ($PSBoundParameters.ContainsKey($key)) { $options[$key] = $PSBoundParameters[$key] } }
    $report = Invoke-SafeAndroidUpdate @options
    $report | ConvertTo-Json -Depth 6
    if ($CheckOnly) { Write-Output '僅檢查完成；尚未安裝或啟動。' }
    else { Write-Output '更新、安裝後核對及單次啟動命令成功；不保證 App 已在前景。' }
    exit 0
} catch {
    [Console]::Error.WriteLine("安全更新停止：$($_.Exception.Message) 請確認工具／權限並以 -Serial、-UserId、-Package 指定既有目標；勿以卸載／清資料處理。")
    exit 1
} finally {
    if ($workspace -and (Test-Path -LiteralPath $workspace)) { Remove-Item -LiteralPath $workspace -Recurse -Force }
}
