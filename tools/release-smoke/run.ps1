# 发布冒烟：发布到本地 staging repo，拉起联调服务，再用 Gradle 与 Maven 两个外部消费者
# 各做一次 status 查询。任何一步失败都以非零码退出；服务端在 finally 中回收。
#
# 为什么单独写脚本而不是挂进 build：这是消费端验收，不是本仓库的编译任务；
# 它需要真实进程、真实端口和两个独立构建系统，放进入常规 check 会让每次构建都变慢。

param(
    [int]$Port = 25565,
    [string]$Version = "0.1.0",
    [string]$HostName = "127.0.0.1"
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$gradlew = Join-Path $root "gradlew.bat"
$serverScript = Join-Path $root "testing\minestom\build\install\minestom\bin\minestom.bat"
$gradleFixture = Join-Path $PSScriptRoot "gradle"
$mavenFixture = Join-Path $PSScriptRoot "maven"
$serverOut = Join-Path $env:TEMP "pickaxecraft-release-smoke-server.out"
$serverErr = Join-Path $env:TEMP "pickaxecraft-release-smoke-server.err"

# 分发包是 Java 25 字节码（共享库才是 21）；JAVA_HOME 不是 25 时明确失败，
# 不要让它表现成 UnsupportedClassVersionError 这种二义性错误。
$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    throw "JAVA_HOME 未设置；运行 CLI 与联调服务需要 JDK 25。"
}
$releaseFile = Join-Path $javaHome "release"
if (-not (Test-Path $releaseFile) -or ((Get-Content $releaseFile -Raw) -notmatch 'JAVA_VERSION="25')) {
    throw "JAVA_HOME=$javaHome 不是 JDK 25；运行 CLI 与联调服务需要 JDK 25（共享库仍按 Java 21 字节码发布）。"
}

Write-Host "==> publish $Version to build/staging-repo"
& $gradlew "-Pversion=$Version" publishAllPublicationsToStagingRepository --console=plain
if ($LASTEXITCODE -ne 0) { throw "发布到 staging repo 失败" }

Write-Host "==> build test server distribution"
& $gradlew :testing:minestom:installDist --console=plain
if ($LASTEXITCODE -ne 0) { throw "联调服务打包失败" }

$server = $null
try {
    Write-Host "==> start test server on $HostName`:$Port"
    $server = Start-Process -FilePath $serverScript -ArgumentList @("--port", $Port) `
        -NoNewWindow -PassThru -RedirectStandardOutput $serverOut -RedirectStandardError $serverErr

    # 等“就绪”那一行而不是固定 sleep：它是服务端自己打印的合同（hook 已注册、端口已监听）。
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        if ($server.HasExited) {
            throw "联调服务提前退出（code=$($server.ExitCode)），见 $serverOut"
        }
        if ((Test-Path $serverOut) -and (Select-String -Path $serverOut -Pattern "event=server.started" -Quiet)) {
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not (Test-Path $serverOut) -or -not (Select-String -Path $serverOut -Pattern "event=server.started" -Quiet)) {
        throw "联调服务在 60 秒内未就绪，见 $serverOut"
    }

    Write-Host "==> gradle consumer"
    & $gradlew -p $gradleFixture run "--args=$HostName $Port" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle 消费者失败" }

    Write-Host "==> maven consumer"
    $staging = (Resolve-Path (Join-Path $root "build\staging-repo")).Path -replace '\\', '/'
    & (Join-Path $mavenFixture "mvnw.cmd") -q -f (Join-Path $mavenFixture "pom.xml") `
        "-Dpickaxe.staging.repo=file:///$staging" `
        compile exec:java "-Dexec.args=$HostName $Port"
    if ($LASTEXITCODE -ne 0) { throw "Maven 消费者失败" }

    Write-Host "release-smoke ok version=$Version host=$HostName port=$Port"
} finally {
    if ($server -and -not $server.HasExited) {
        # 测试服务端没有优雅停机通道（Ctrl+C 只对控制台可用）；这里是临时进程，直接回收。
        & taskkill /PID $server.Id /T /F | Out-Null
    }
}
