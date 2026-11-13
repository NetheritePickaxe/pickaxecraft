#!/usr/bin/env bash
# 发布冒烟：发布到本地 staging repo，拉起联调服务，再用 Gradle 与 Maven 两个外部消费者
# 各做一次 status 查询。与 run.ps1 等价，供 Linux/macOS 使用。
set -euo pipefail

port="${1:-25565}"
version="${2:-0.1.0}"
host="${3:-127.0.0.1}"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
gradlew="$root/gradlew"
server_script="$root/testing/minestom/build/install/minestom/bin/minestom"
gradle_fixture="$root/tools/release-smoke/gradle"
maven_fixture="$root/tools/release-smoke/maven"
server_out="$(mktemp)"

if [[ -z "${JAVA_HOME:-}" ]]; then
    echo "JAVA_HOME 未设置；运行 CLI 与联调服务需要 JDK 25。" >&2
    exit 1
fi
if ! grep -q 'JAVA_VERSION="25' "$JAVA_HOME/release" 2>/dev/null; then
    echo "JAVA_HOME=$JAVA_HOME 不是 JDK 25；运行 CLI 与联调服务需要 JDK 25。" >&2
    exit 1
fi

echo "==> publish $version to build/staging-repo"
"$gradlew" "-Pversion=$version" publishAllPublicationsToStagingRepository --console=plain

echo "==> build test server distribution"
"$gradlew" :testing:minestom:installDist --console=plain

"$server_script" --port "$port" >"$server_out" 2>&1 &
server_pid=$!
cleanup() {
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
}
trap cleanup EXIT

echo "==> start test server on $host:$port"
ready=0
for _ in $(seq 1 120); do
    if grep -q "event=server.started" "$server_out"; then
        ready=1
        break
    fi
    if ! kill -0 "$server_pid" 2>/dev/null; then
        echo "联调服务提前退出，见 $server_out" >&2
        cat "$server_out" >&2
        exit 1
    fi
    sleep 0.5
done
if [[ "$ready" -ne 1 ]]; then
    echo "联调服务在 60 秒内未就绪，见 $server_out" >&2
    exit 1
fi

echo "==> gradle consumer"
"$gradlew" -p "$gradle_fixture" run "--args=$host $port" --console=plain

echo "==> maven consumer"
"$maven_fixture/mvnw" -q -f "$maven_fixture/pom.xml" \
    "-Dpickaxe.staging.repo=file://$root/build/staging-repo" \
    compile exec:java "-Dexec.args=$host $port"

echo "release-smoke ok version=$version host=$host port=$port"
