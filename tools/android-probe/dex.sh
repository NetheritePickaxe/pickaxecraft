#!/usr/bin/env bash
# 用 Android build-tools 的 d8 对共享产物做一次真实反糖（dex）。
#
# 它验证的是**构建链**：Java 21 字节码能否被 d8 处理成 dex、哪些 API 需要更高的
# minSdk、哪些构件之间存在重复类。它不验证运行时行为——真机/模拟器上的
# Netty、ART 与资源回收仍然是独立门控（见 docs/android-dex-probe.md）。
#
# 用法：
#   tools/android-probe/dex.sh              # 默认 min-api 24 26 34
#   tools/android-probe/dex.sh 24 26        # 指定 min-api
#
# 依赖：ANDROID_HOME（或 ANDROID_SDK_ROOT）指向已安装 platforms;android-36 与 build-tools 的 SDK。

set -euo pipefail

cd "$(dirname "$0")/../.."

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk_root" ] || [ ! -d "$sdk_root" ]; then
    echo "未找到 Android SDK：请设置 ANDROID_HOME" >&2
    exit 1
fi

android_jar="$sdk_root/platforms/android-36/android.jar"
if [ ! -f "$android_jar" ]; then
    echo "缺少 $android_jar（需要 platforms;android-36）" >&2
    exit 1
fi

build_tools="$(ls -d "$sdk_root"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
d8_jar="${build_tools}lib/d8.jar"
if [ ! -f "$d8_jar" ]; then
    echo "缺少 $d8_jar（需要 build-tools）" >&2
    exit 1
fi

echo "==> 生成运行时依赖集合"
./gradlew :app:installDist --console=plain -q

lib_dir="app/build/install/app/lib"
if [ ! -d "$lib_dir" ]; then
    echo "未找到 $lib_dir" >&2
    exit 1
fi

inputs=()
for jar in "$lib_dir"/*.jar; do
    case "$jar" in
        # 组合根是 Java 25（major 69），本来就不进 Android 产物；d8 会直接拒绝它。
        */app-*.jar) continue ;;
        # org.cloudburstmc.math:api 的 57 个类全部包含在 :immutable 里，
        # 同时打包会让 d8 报 Duplicate class；Android 上必须先 exclude 掉 api。
        */api-2.0.jar) continue ;;
    esac
    inputs+=("$jar")
done

echo "==> 输入构件 ${#inputs[@]} 个；d8 来自 $build_tools"

if [ $# -eq 0 ]; then
    set -- 24 26 34
fi

status=0
for api in "$@"; do
    out="build/android-probe/dex-$api"
    log="build/android-probe/d8-$api.log"
    rm -rf "$out" "$log"
    mkdir -p "$out"

    if java -cp "$d8_jar" com.android.tools.r8.D8 \
        --min-api "$api" \
        --lib "$android_jar" \
        --output "$out" \
        "${inputs[@]}" > "$log" 2>&1; then
        warnings=$(grep -c '^Warning' "$log" || true)
        min_sdk_warnings=$(grep -c 'only supported starting with Android' "$log" || true)
        dex_count=$(find "$out" -name '*.dex' | wc -l)
        dex_size=$(du -sh "$out" | cut -f1)
        echo "min-api $api: dex=$dex_count size=$dex_size warnings=$warnings minSdkWarnings=$min_sdk_warnings"
    else
        echo "min-api $api: d8 失败，见 $log" >&2
        status=1
    fi
done

exit $status
