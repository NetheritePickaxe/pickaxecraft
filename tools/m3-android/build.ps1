# Build the M3 Android evidence host APK.
#
# Why not AGP: the main build is not an Android build, and pulling AGP into it
# would tax every contributor for an evidence-only artifact. This script drives
# the SDK tools directly (aapt2 / d8 / zipalign / apksigner), the same way
# tools/android-probe/dex.sh drives d8.
#
# Requirements: ANDROID_HOME with platforms;android-36 and build-tools;36.0.0,
# JDK 21+ on JAVA_HOME, python on PATH, and a populated Gradle cache (the Kotlin
# compiler jar is reused from it).
#
# Usage:
#   powershell -File tools/m3-android/build.ps1
#   powershell -File tools/m3-android/build.ps1 -SkipInstallDist

param(
    [switch]$SkipInstallDist,
    [int]$MinSdk = 26,
    [int]$TargetSdk = 36
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (Resolve-Path (Join-Path $here "..\..")).Path
Set-Location $repo

function Fail([string]$message) {
    Write-Host "ERROR: $message" -ForegroundColor Red
    exit 1
}

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk -or -not (Test-Path $sdk)) { Fail "ANDROID_HOME is not set or does not exist" }

$androidJar = Join-Path $sdk "platforms\android-$TargetSdk\android.jar"
if (-not (Test-Path $androidJar)) { Fail "missing $androidJar (install platforms;android-$TargetSdk)" }

$buildTools = Get-ChildItem (Join-Path $sdk "build-tools") -Directory |
    Sort-Object { [version]$_.Name } | Select-Object -Last 1
if (-not $buildTools) { Fail "no build-tools installed" }
$aapt2 = Join-Path $buildTools.FullName "aapt2.exe"
$zipalign = Join-Path $buildTools.FullName "zipalign.exe"
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"
$d8Jar = Join-Path $buildTools.FullName "lib\d8.jar"
foreach ($tool in @($aapt2, $zipalign, $apksigner, $d8Jar)) {
    if (-not (Test-Path $tool)) { Fail "missing $tool" }
}
Write-Host "build-tools: $($buildTools.Name)"

$out = Join-Path $repo "build\m3-android"
$classes = Join-Path $out "classes"
$dexDir = Join-Path $out "dex"
$apk = Join-Path $out "pickaxecraft-android-host.apk"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $dexDir | Out-Null

if (-not $SkipInstallDist) {
    Write-Host "==> gradlew :app:installDist"
    & (Join-Path $repo "gradlew.bat") ":app:installDist" "--console=plain"
    if ($LASTEXITCODE -ne 0) { Fail ":app:installDist failed" }
}

$libDir = Join-Path $repo "app\build\install\pickaxecraft\lib"
if (-not (Test-Path $libDir)) { Fail "missing $libDir" }

# The app jar is Java 25 (major 69) and d8 rejects it; api-2.0 duplicates 57
# classes from immutable-2.0 and Android treats that as a hard error.
$jars = Get-ChildItem $libDir -Filter *.jar | Where-Object {
    $_.Name -notlike "app-*" -and $_.Name -ne "api-2.0.jar"
}
if ($jars.Count -eq 0) { Fail "no input jars under $libDir" }
Write-Host "==> runtime jars: $($jars.Count)"

$libsCp = ($jars | ForEach-Object { $_.FullName }) -join ";"

# ---------------------------------------------------------------- javac
# Two groups, because one of them stands in for a platform API:
#
# * javax.naming.* -- Android has no JNDI at all (API 36 android.jar carries no
#   javax/naming entry), while mcprotocollib's NettyHelper.<clinit> needs
#   InitialDirContext to exist for any connection to start. These stubs are
#   compiled with --release 8: javac refuses to emit a package owned by a JDK
#   module (java.naming) in module-aware mode, and the class file version does
#   not matter for d8.
# * everything else -- the normal host code, against android.jar.
$shimSources = @(Get-ChildItem (Join-Path $here "host\src\main\java") -Recurse -Filter *.java |
    Where-Object { $_.FullName -like "*\javax\naming\*" } | ForEach-Object { $_.FullName })
$javaSources = @(Get-ChildItem (Join-Path $here "host\src\main\java") -Recurse -Filter *.java |
    Where-Object { $_.FullName -notlike "*\javax\naming\*" } | ForEach-Object { $_.FullName })

Write-Host "==> javac (host java sources)"
if ($shimSources.Count -gt 0) {
    & javac "--release" "8" "-Xlint:-options" "-classpath" $androidJar "-d" $classes $shimSources
    if ($LASTEXITCODE -ne 0) { Fail "javac (javax.naming shim) failed" }
}
if ($javaSources.Count -gt 0) {
    # Pass the array itself: @array splatting does not work for a one-element
    # string array in Windows PowerShell (it is unpacked per character).
    & javac "--release" "21" "-classpath" "$androidJar;$classes" "-d" $classes $javaSources
    if ($LASTEXITCODE -ne 0) { Fail "javac failed" }
}

# --------------------------------------------------------------- kotlinc
# Reuse the compiler that the main build already resolved: same version as
# kotlin-stdlib on the classpath, so client-core metadata is readable.
$stdlibJar = $jars | Where-Object { $_.Name -like "kotlin-stdlib-2*.jar" } | Select-Object -First 1
if (-not $stdlibJar) { Fail "kotlin-stdlib not found in $libDir" }
$kotlinVersion = ($stdlibJar.Name -replace "^kotlin-stdlib-", "" -replace "\.jar$", "")
$compilerRoot = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin"
$compilerJar = Get-ChildItem (Join-Path $compilerRoot "kotlin-compiler-embeddable\$kotlinVersion") -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $compilerJar) {
    Fail "kotlin-compiler-embeddable $kotlinVersion not in the Gradle cache; run .\gradlew.bat :app:compileKotlin once"
}

# The compiler itself needs kotlinx-coroutines (its IntelliJ core links against
# it). Reuse the jars already sitting in the app runtime directory instead of
# resolving a second compiler toolchain.
$compilerCp = @($compilerJar.FullName) + ($jars | ForEach-Object { $_.FullName })

Write-Host "==> kotlinc $kotlinVersion (jvm-target 21)"
$kotlinSources = Join-Path $here "host\src\main\kotlin"
$compileCp = "$androidJar;$libsCp;$classes"
& java "-cp" ($compilerCp -join ";") "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler" `
    "-no-reflect" "-no-stdlib" "-no-jdk" `
    "-jvm-target" "21" `
    "-classpath" $compileCp `
    "-d" $classes $kotlinSources
if ($LASTEXITCODE -ne 0) { Fail "kotlinc failed" }

# -------------------------------------------------------------- resources
Write-Host "==> aapt2 link"
$baseApk = Join-Path $out "base.apk"
& $aapt2 "link" "-o" $baseApk `
    "-I" $androidJar `
    "--manifest" (Join-Path $here "host\AndroidManifest.xml") `
    "--min-sdk-version" "$MinSdk" `
    "--target-sdk-version" "$TargetSdk" `
    "--version-code" "1" `
    "--version-name" "0.1.0"
if ($LASTEXITCODE -ne 0) { Fail "aapt2 link failed" }

# -------------------------------------------------------------------- d8
# d8 takes class files and jars, never a directory: package the compiled host
# classes first (same reason the runtime jars are passed one by one).
$classesJar = Join-Path $out "classes.jar"
& jar "--create" "--file" $classesJar "-C" $classes "."
if ($LASTEXITCODE -ne 0) { Fail "jar failed" }

Write-Host "==> d8 (min-api $MinSdk)"
$d8Args = @("-cp", $d8Jar, "com.android.tools.r8.D8",
    "--lib", $androidJar,
    "--min-api", "$MinSdk",
    "--output", $dexDir,
    $classesJar) + ($jars | ForEach-Object { $_.FullName })
& java @d8Args
if ($LASTEXITCODE -ne 0) { Fail "d8 failed" }
$dexFiles = Get-ChildItem $dexDir -Filter "*.dex"
Write-Host "    dex files: $($dexFiles.Count)"

# ------------------------------------------------------------------ apk
Write-Host "==> package apk"
& python (Join-Path $here "package_apk.py") $baseApk $apk $dexDir ($jars | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { Fail "packaging failed" }

Write-Host "==> zipalign + sign"
$aligned = Join-Path $out "aligned.apk"
& $zipalign "-f" "-p" "4" $apk $aligned
if ($LASTEXITCODE -ne 0) { Fail "zipalign failed" }

$keystore = Join-Path $out "..\m3-android-debug.keystore"
# Kept outside build\m3-android on purpose: the APK must be signed with the same
# debug key across builds, otherwise every rebuild needs an uninstall first.
$keystore = [System.IO.Path]::GetFullPath($keystore)
if (-not (Test-Path $keystore)) {
    & keytool "-genkeypair" "-keystore" $keystore "-storepass" "android" "-keypass" "android" `
        "-alias" "androiddebugkey" "-keyalg" "RSA" "-keysize" "2048" "-validity" "10000" `
        "-dname" "CN=Android Debug,O=Android,C=US"
    if ($LASTEXITCODE -ne 0) { Fail "keytool failed" }
}
& $apksigner "sign" "--ks" $keystore "--ks-pass" "pass:android" "--key-pass" "pass:android" `
    "--ks-key-alias" "androiddebugkey" "--out" $apk $aligned
if ($LASTEXITCODE -ne 0) { Fail "apksigner failed" }

Write-Host ""
Write-Host "APK: $apk"
Write-Host "$((Get-Item $apk).Length / 1MB) MB"
exit 0
