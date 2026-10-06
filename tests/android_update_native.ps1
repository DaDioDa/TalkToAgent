param([string]$Config)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$c = Get-Content -LiteralPath $Config -Raw -Encoding UTF8 | ConvertFrom-Json
Import-Module "$PSScriptRoot/../tools/android/SafeUpdate.psm1" -Force
$runner = {
    param($File, [string[]]$Arguments)
    Invoke-UpdateNativeCommand $c.Python (@($c.Probe, $c.Workspace, $File) + $Arguments)
}.GetNewClosure()
try {
    $r = Invoke-SafeAndroidUpdate -ProjectRoot $c.Root -Workspace $c.Workspace -Tools @{ Adb = 'adb'; Aapt = 'aapt'; Signer = 'signer'; Gradle = 'gradle' } -CommandRunner $runner
    $r | ConvertTo-Json -Depth 6 -Compress
} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }
