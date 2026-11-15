# Drive the Termux terminal from adb, for the Phase 4.2 desktop-JVM-on-device run.
#
# Termux keeps its home under /data/data/com.termux, which adb cannot read, and
# its RUN_COMMAND service needs a permission the shell user does not hold on this
# ROM. What does work on every ROM: focus the terminal view and inject key events
# with `input text`, then read the screen back from a uiautomator dump.
#
# Usage:
#   powershell -File tools/m3-android/termux/termux.ps1 -Command "java -version"
#   powershell -File tools/m3-android/termux/termux.ps1 -Read
#   powershell -File tools/m3-android/termux/termux.ps1 -Read -Lines 40
#   powershell -File tools/m3-android/termux/termux.ps1 -Command "pkg install -y openjdk-25" -WaitSeconds 120 -Read -Lines 40
#
# Limitations: `input text` cannot type a single quote, and `%s` means a space.
# Keep commands free of both.

param(
    [string]$Command,
    [switch]$Read,
    [int]$Lines = 25,
    [int]$WaitSeconds = 2
)

# adb writes ordinary progress/warning lines to stderr, which Windows PowerShell
# turns into error records; with "Stop" a benign "Activity not started" would
# abort the run. Failures are checked explicitly where they matter.
$ErrorActionPreference = "Continue"
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb not found under ANDROID_HOME ($env:ANDROID_HOME)"
}

function Get-TerminalText {
    & $adb shell "uiautomator dump /sdcard/termux-drive.xml" | Out-Null
    $xml = (& $adb shell "cat /sdcard/termux-drive.xml" 2>&1 | Out-String)
    # uiautomator switches to single quotes around content-desc when the value
    # itself contains a double quote, so both forms have to be accepted.
    $match = [regex]::Match(
        $xml,
        'resource-id="com\.termux:id/terminal_view"[^>]*content-desc="([^"]*)"'
    )
    if (-not $match.Success) {
        $match = [regex]::Match(
            $xml,
            "resource-id=`"com\.termux:id/terminal_view`"[^>]*content-desc='([^']*)'"
        )
    }
    if (-not $match.Success) {
        throw "terminal_view not found; is Termux in the foreground?"
    }
    $text = $match.Groups[1].Value
    $text = $text -replace '&#10;', "`n" -replace '&lt;', '<' -replace '&gt;', '>' `
        -replace '&#39;', "'" -replace '&quot;', '"' -replace '&amp;', '&'
    return $text
}

if ($Command) {
    if ($Command -match "'") {
        throw "a single quote cannot be typed with input text"
    }
    # Key injection goes to whatever window has focus; the device may have gone
    # to the launcher or another app between calls, so bring Termux forward first.
    & $adb shell "input keyevent KEYCODE_WAKEUP" | Out-Null
    & $adb shell "am start -n com.termux/com.termux.app.TermuxActivity" 2>&1 | Out-Null
    Start-Sleep -Milliseconds 600
    $typed = $Command -replace ' ', '%s'
    & $adb shell "input text '$typed'" | Out-Null
    & $adb shell "input keyevent 66" | Out-Null
}

if ($Read -or -not $Command) {
    Start-Sleep -Seconds $WaitSeconds
    $rows = [System.Collections.Generic.List[string]]::new()
    $rows.AddRange([string[]]((Get-TerminalText) -split "`n"))
    # The dump pads the screen with blank rows; drop them before taking the tail.
    while ($rows.Count -gt 0 -and $rows[$rows.Count - 1].Trim().Length -eq 0) {
        $rows.RemoveAt($rows.Count - 1)
    }
    $rows | Select-Object -Last $Lines
}
