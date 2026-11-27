#!/bin/sh
# 不靠 Gradle 出 APK：aapt2 → javac → d8 → zipalign → apksigner。
#
# 为什么不用 ./gradlew：AGP 会从 Google Maven 拉一份 linux-x86_64 的 aapt2，
# 在 arm64 的机器（手机、PRoot 容器、Apple Silicon 的 Linux 虚拟机）上跑不了。
# 这个壳没有一条第三方依赖（不用 AndroidX，资源就几个 xml），直接把五步拼起来
# 反而更快，也能在任何 aarch64 Linux 上复现。
#
#   ANDROID_JAR=/path/android.jar ./scripts/build-apk-local.sh
#
# 需要的：
#   - JDK 17（javac、keytool）
#   - Android SDK：build-tools 的 lib/d8.jar、lib/apksigner.jar，platforms 里的 android.jar
#   - aarch64 的 aapt2 / zipalign：仓库自带 scripts/tools/aarch64/，
#     x86_64 机器上自动改用 $SDK/build-tools/<ver>/ 里那份
#
# versionCode 规则（重要，别再搞错）：versionCode = major*10000 + minor*1000 + patch。
#   1.8.36 -> 18036；1.8.68 -> 18068。
#   历史：1.8.4=184 … 1.8.36=18036 用的是旧的 1xx 规则；1.8.36 起实际用 18xxx 规则。
#   不显式传 VERSION_CODE 时，本脚本按 VERSION_NAME 自动推导；patch>999 直接报错。
#   不要修改已发布历史 tag 的 versionCode。
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SDK="${ANDROID_SDK:-${ANDROID_HOME:-/opt/android-sdk}}"
SDK="${SDK%/}"
BT_VER="${BUILD_TOOLS_VERSION:-}"
OUT_DIR="${OUT_DIR:-$ROOT/dist}"
VERSION_NAME="${VERSION_NAME:-1.0}"
APP_ID="com.magpie.android"

die() { echo "错误：$*" >&2; exit 1; }

# --- versionCode 推导（重要）---------------------------------------
# 实测真实规则：1.8.36 的 APK versionCode = 18036 = major*10000 + minor*1000 + patch。
#   历史：1.8.4=184 … 1.8.36=18036（旧的 1xx 规则已废弃）；1.8.36 起实际用 18xxx 规则。
#   不要修改已发布历史 tag 的 versionCode。
# 未显式传 VERSION_CODE 时，一律由 VERSION_NAME 推导；patch>999 直接报错（会与 minor 进位号码冲突）。
if [ -z "${VERSION_CODE:-}" ]; then
    VERSION_CODE="$(printf '%s' "$VERSION_NAME" | awk -F. '
        {
            maj=$1; sub(/^[vV]/, "", maj);
            min=$2;
            pat=$3; if (pat == "") pat = "0";
            if (maj !~ /^[0-9]+$/ || min !~ /^[0-9]+$/ || pat !~ /^[0-9]+$/) { print "ERRPARSE"; exit }
            if (pat + 0 > 999) { print "ERRPATCH"; exit }
            printf "%d", (maj + 0) * 10000 + (min + 0) * 1000 + (pat + 0)
        }')"
    case "$VERSION_CODE" in
        ERRPARSE) die "无法从 VERSION_NAME 推导 versionCode：'$VERSION_NAME'（需 major.minor.patch 纯数字）" ;;
        ERRPATCH) die "VERSION_NAME '$VERSION_NAME' 的 patch 超过 999，无法推导 versionCode" ;;
    esac
    echo "→ versionCode 由 VERSION_NAME 推导：$VERSION_NAME -> $VERSION_CODE"
fi

# --- 工具 -----------------------------------------------------------

arch="$(uname -m)"
AAPT2="${AAPT2:-}"
ZIPALIGN="${ZIPALIGN:-}"
if [ "$arch" = "aarch64" ] || [ "$arch" = "arm64" ]; then
    [ -x "$ROOT/scripts/tools/aarch64/aapt2" ] && AAPT2="$ROOT/scripts/tools/aarch64/aapt2"
    [ -x "$ROOT/scripts/tools/aarch64/zipalign" ] && ZIPALIGN="$ROOT/scripts/tools/aarch64/zipalign"
fi

# build-tools 目录：容器里可能叫 android-15 之类，按有没有 aapt2 认
bt_dir() {
    for d in "$SDK"/build-tools/* "$SDK"/android-*; do
        [ -d "$d" ] || continue
        [ -f "$d/lib/d8.jar" ] && echo "$d" && return 0
    done
    return 1
}

BT="$(bt_dir || true)"
[ -n "$BT" ] || die "找不到 build-tools（要有 lib/d8.jar）。设 ANDROID_SDK 指向 SDK 根目录。"

[ -n "$AAPT2" ] || AAPT2="$BT/aapt2"
[ -n "$ZIPALIGN" ] || ZIPALIGN="$BT/zipalign"
[ -x "$AAPT2" ] || die "找不到可执行的 aapt2（$AAPT2）。arm64 上请保留 scripts/tools/aarch64/aapt2。"
[ -x "$ZIPALIGN" ] || die "找不到可执行的 zipalign（$ZIPALIGN）"

ANDROID_JAR="${ANDROID_JAR:-}"
if [ -z "$ANDROID_JAR" ]; then
    # 需要一份带 resources.arsc 的 android.jar；API 30 那份能被 build-tools 33 的
    # aapt2 读，API 35 那份不行（arsc 版本太新）。
    for f in "$SDK"/platforms/*/android.jar "$SDK"/android-*/android.jar; do
        [ -f "$f" ] || continue
        ANDROID_JAR="$f"
        case "$f" in
            *android-30/*|*android-11/*) break ;;
        esac
    done
fi
[ -f "$ANDROID_JAR" ] || die "找不到 android.jar，设 ANDROID_JAR 指一个带 resources.arsc 的（API ≤ 30 最好）"

LAMBDA_STUBS="$BT/core-lambda-stubs.jar"
[ -f "$LAMBDA_STUBS" ] || LAMBDA_STUBS=""

command -v javac >/dev/null 2>&1 || die "找不到 javac（要 JDK 17）"
JAVA="${JAVA:-java}"

echo "aapt2     : $AAPT2"
echo "android.jar: $ANDROID_JAR"
echo "build-tools: $BT"

# --- 二进制 ---------------------------------------------------------
# SKIP_KERNEL=1：打「壳子包」（不含 98MB 内核）。手机上已有内核
# （files/magpie/magpie 存在，ensureBinary 绝不覆盖）时直接覆盖装壳包即可，
# 内核保持不变——日常 UI 迭代用这个，不用每次传 99MB。
# 正式发布不带此变量（合内核整包）。
if [ "${SKIP_KERNEL:-0}" = "1" ]; then
    echo "→ SKIP_KERNEL=1：打壳子包（不含内核），产物名加 -shell 后缀"
    OUT_SUFFIX="-shell"
else
    if [ ! -f app/src/main/assets/bin/magpie.bin ]; then
        echo "assets 里没有二进制，先跑 scripts/build-magpie.sh + scripts/sync-binary.sh" >&2
        echo "（或 SKIP_KERNEL=1 打壳子包）" >&2
        die "缺 app/src/main/assets/bin/magpie.bin"
    fi
    OUT_SUFFIX=""
fi

# --- 资源 -----------------------------------------------------------

BUILD="$ROOT/build-local"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT_DIR"

# AGP 8 不允许 AndroidManifest 里写 package（用 namespace），但 aapt2 要；
# 构建时复制一份补上，源文件保持干净。
sed "s|<manifest |<manifest package=\"$APP_ID\" |" \
    app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"

echo "→ aapt2 compile"
"$AAPT2" compile --dir app/src/main/res -o "$BUILD/res.zip"

echo "→ aapt2 link"
"$AAPT2" link -I "$ANDROID_JAR" \
    --manifest "$BUILD/AndroidManifest.xml" \
    -o "$BUILD/base.apk" \
    --java "$BUILD/gen" \
    -R "$BUILD/res.zip" \
    --min-sdk-version 24 \
    --target-sdk-version 28 \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --auto-add-overlay

# --- Java -----------------------------------------------------------

echo "→ javac"
BOOTCP="$ANDROID_JAR"
[ -n "$LAMBDA_STUBS" ] && BOOTCP="$ANDROID_JAR:$LAMBDA_STUBS"
# 第三方 jar（Shizuku API）：只在 classpath 里，不打进 bootclasspath
LIBS_CP=""
if [ -d app/libs ]; then
    for j in app/libs/*.jar; do
        [ -f "$j" ] || continue
        LIBS_CP="$LIBS_CP:$j"
    done
fi
javac -nowarn -Xlint:-options -source 8 -target 8 \
    -bootclasspath "$BOOTCP" \
    -classpath "${LIBS_CP#:}" \
    -d "$BUILD/classes" \
    "$BUILD/gen/$(echo "$APP_ID" | tr '.' '/')/R.java" \
    app/src/main/java/$(echo "$APP_ID" | tr '.' '/')/*.java

echo "→ d8"
# shellcheck disable=SC2046
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 24 --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name '*.class') \
    $(ls app/libs/*.jar 2>/dev/null)

# --- 组装 -----------------------------------------------------------

echo "→ 打包"
python3 - "$BUILD" "$ROOT" <<'PY'
import os, sys, zipfile

build, root = sys.argv[1], sys.argv[2]
base = os.path.join(build, 'base.apk')
out = os.path.join(build, 'unsigned.apk')
if os.path.exists(out):
    os.remove(out)

with zipfile.ZipFile(base) as zin, zipfile.ZipFile(out, 'w') as zout:
    for info in zin.infolist():
        zi = zipfile.ZipInfo(info.filename, date_time=(2026, 1, 1, 0, 0, 0))
        zi.compress_type = info.compress_type
        zi.external_attr = info.external_attr
        zout.writestr(zi, zin.read(info.filename))
    # 代码
    with open(os.path.join(build, 'dex', 'classes.dex'), 'rb') as f:
        zout.writestr(zipfile.ZipInfo('classes.dex', (2026, 1, 1, 0, 0, 0)), f.read(), zipfile.ZIP_DEFLATED)
    # 随 APK 保留上游 magpie 的 MIT 版权与许可声明。
    with open(os.path.join(root, 'app/src/main/assets/MAGPIE_LICENSE.txt'), 'rb') as f:
        zout.writestr(zipfile.ZipInfo('assets/MAGPIE_LICENSE.txt', (2026, 1, 1, 0, 0, 0)), f.read(), zipfile.ZIP_DEFLATED)
    # 二进制：不压缩，首启是拷贝而不是解压（aaptOptions noCompress "bin" 同义）
    # SKIP_KERNEL=1（壳子包）时不写内核——手机上已装的内核不会被覆盖
    skip_kernel = os.environ.get('SKIP_KERNEL', '0') == '1'
    if skip_kernel:
        print('    （壳子包：不含内核）')
    elif os.path.exists(os.path.join(root, 'app/src/main/assets/bin/magpie.bin')):
        with open(os.path.join(root, 'app/src/main/assets/bin/magpie.bin'), 'rb') as f:
            zout.writestr(zipfile.ZipInfo('assets/bin/magpie.bin', (2026, 1, 1, 0, 0, 0)), f.read(), zipfile.ZIP_STORED)
    else:
        print('    （壳子包：不含内核）')
print('   ', out, os.path.getsize(out), 'bytes')
PY

echo "→ zipalign"
"$ZIPALIGN" -p -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

# --- 签名 -----------------------------------------------------------

# 签名口令/别名一律优先从环境变量注入：KS_PASS / KEY_ALIAS（KEYSTORE 也可覆盖）。
# 发布时务必显式传真实发布 keystore 与口令：KEYSTORE=... KS_PASS=... KEY_ALIAS=...；
# 未设置时回退到仓库内的调试默认值并打印警告（仅限本地调试，切勿用于对外发布）。
# 注意：密钥轮换不可擅自做（换发布钥会让已发布版本的更新签名不一致、无法覆盖升级）。
KEYSTORE="${KEYSTORE:-$ROOT/scripts/debug.keystore}"
if [ -z "${KS_PASS:-}" ]; then
    KS_PASS="android"
    echo "警告：未设置 KS_PASS，回退到调试默认口令（仅限本地调试；发布前必须用环境变量注入真实口令）" >&2
fi
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
if [ ! -f "$KEYSTORE" ]; then
    echo "→ 生成调试签名（$KEYSTORE）"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

APK="$OUT_DIR/magpie-android-$VERSION_NAME${OUT_SUFFIX}.apk"
echo "→ apksigner"
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --min-sdk-version 24 --v1-signing-enabled true --v2-signing-enabled true \
    --out "$APK" "$BUILD/aligned.apk"

rm -f "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
echo
echo "APK: $APK（$(du -h "$APK" | cut -f1)）"
