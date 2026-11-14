# Drive the M3 Android evidence host on a connected device.
#
# Steps: wake the device, install the APK, adb reverse the game port, start the
# Activity with the run parameters, wait for the report to reach host.done or
# host.failed, then pull the report into tools/m3-android/evidence/.
#
# Assumes the Minestom test server from :testing:minestom is already listening
# on -Port (run it in another shell; this script never starts it).
#
# Usage:
#   powershell -File tools/m3-android/run.ps1
#   powershell -File tools/m3-android/run.ps1 -Action join -SkipInstall
#   powershell -File tools/m3-android/run.ps1 -Evidence C:\tmp\report.txt

param(
    [string]$Action = "all",
    [string]$Server = "127.0.0.1",
    [int]$Port = 25565,
    [string]$Username = "AndroidHost",
    [int]$Cycles = 20,
    [int]$TimeoutSeconds = 300,
    [int]$InstallTimeoutSeconds = 300,
    [switch]$SkipInstall,
    [string]$Evidence = ""
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (Resolve-Path (Join-Path $here "..\..")).Path

$package = "org.pickaxe.androidhost"
$apk = Join-Path $repo "build\m3-android\pickaxecraft-android-host.apk"
$remoteReport = "/sdcard/Android/data/$package/files/report.txt"
if (-not $Evidence) {
    $Evidence = Join-Path $here "evidence\$((Get-Date).ToString('yyyy-MM-dd'))-$Action.txt"
}

function Fail([string]$message) {
    Write-Host "ERROR: $message" -ForegroundColor Red
    exit 1
}

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { Fail "ANDROID_HOME is not set" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { Fail "missing $adb" }

$devices = @(& $adb devices | Where-Object { $_ -match "\sdevice$" })
if ($devices.Count -eq 0) { Fail "no adb device; connect one and authorize it" }

# Screen on: on the PLC110 test device `adb install` stalls for minutes while the
# screen dozes, so wake it and keep it awake for the whole run.
& $adb shell svc power stayon true | Out-Null
& $adb shell input keyevent KEYCODE_WAKEUP | Out-Null

if (-not $SkipInstall) {
    if (-not (Test-Path $apk)) { Fail "missing $apk; run tools/m3-android/build.ps1 first" }
    Write-Host "==> install $apk"
    $install = Start-Job -ScriptBlock {
        param($adb, $apk) & $adb install -r -t $apk 2>&1
    } -ArgumentList $adb, $apk
    if (-not (Wait-Job $install -Timeout $InstallTimeoutSeconds)) {
        Stop-Job $install
        Remove-Job $install -Force
        Fail "install did not finish in $InstallTimeoutSeconds s; wake the device and retry"
    }
    $result = Receive-Job $install
    Remove-Job $install -Force
    $result | ForEach-Object { Write-Host "    $_" }
    if (-not ($result -match "Success")) { Fail "install failed" }
}

Write-Host "==> adb reverse tcp:$Port"
& $adb reverse "tcp:$Port" "tcp:$Port" | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "adb reverse failed" }

Write-Host "==> start $package (action=$Action host=$Server port=$Port cycles=$Cycles)"
& $adb shell am force-stop $package | Out-Null
& $adb shell am start -n "$package/.MainActivity" `
    -e action $Action -e host $Server -ei port $Port -e username $Username -ei cycles $Cycles | Out-Null

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$text = @()
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    $text = @(& $adb shell "cat $remoteReport" 2>$null)
    if ($text -match "event=host\.(done|failed)") { break }
}

if (-not ($text -match "event=host\.(done|failed)")) {
    Fail "no host.done/host.failed in $remoteReport after $TimeoutSeconds s"
}

New-Item -ItemType Directory -Force (Split-Path -Parent $Evidence) | Out-Null
& $adb pull $remoteReport $Evidence 2>$null | Out-Null
Write-Host "==> report: $Evidence"
$text | ForEach-Object { Write-Host "    $_" }

if ($text -match "event=host\.failed") {
    Fail "run failed; see the report above"
}
Write-Host "==> host.done"
exit 0
