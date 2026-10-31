#!/system/bin/sh
# ═══════════════════════════════════════════════════════════════
# HY 文件管理 · 发布前门禁
#
# 用法：sh tools/preflight.sh [--fix-claims]
#
# 存在意义：前三轮审查反复发现「发布说明声称已删/已加，代码实际没有」，
# 靠人工记忆守不住，必须机器验证。
# ═══════════════════════════════════════════════════════════════
set -e
cd "$(dirname "$0")/.."

FAIL=0
err()  { echo "  ❌ $*"; FAIL=$((FAIL+1)); }
ok()   { echo "  ✅ $*"; }
warn() { echo "  ⚠️  $*"; }

echo "═══ 1. 版本号三处一致性 ═══"
VC=$(grep -oP 'versionCode\s*=\s*\K\d+' app/build.gradle.kts | head -1)
VN=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)
JVC=$(python3 -c "import json;d=json.load(open('versions.json'));print(d['versions'][0]['versionCode'])" 2>/dev/null || echo "")
JVN=$(python3 -c "import json;d=json.load(open('versions.json'));print(d['versions'][0]['ver'])" 2>/dev/null || echo "")

[ -n "$VC" ]  && ok "gradle versionCode = $VC"  || err "读不到 versionCode"
[ -n "$VN" ]  && ok "gradle versionName = $VN"  || err "读不到 versionName"
[ "$VC" = "$JVC" ] && ok "versionCode 一致" || err "versionCode 不一致：gradle=$VC versions.json=$JVC"
[ "$VN" = "$JVN" ] && ok "versionName 一致" || err "versionName 不一致：gradle=$VN versions.json=$JVN"
grep -q "$VN" README.md && ok "README 已含 $VN" || err "README 未更新到 $VN"

echo
echo "═══ 2. 发布说明 vs 代码（claims 反查）═══"
# 从最新一条 versions.json 的 body 里提取「删除 xxx」/「新增 xxx」这类断言，
# 逐个到代码里反查。这是把「人工审查」变成「机器门禁」的关键一步。
BODY=$(python3 -c "import json;d=json.load(open('versions.json'));print(d['versions'][0].get('body',''))" 2>/dev/null || echo "")

# 只取「删除 <方法名>」形式；排除路径片段（如 ui/Anim.java → ui）
echo "$BODY" | grep -oP '删除\s+\K[a-zA-Z][a-zA-Z0-9_]{3,}(?=[\s，,）)]|$)' | sort -u | while read -r sym; do
    [ -z "$sym" ] && continue
    # 通用词黑名单：这些不是方法名
    case "$sym" in
        file|files|this|that|with|from|code|line|item|list|page) continue ;;
    esac
    if grep -rq --include='*.java' "\b$sym\b" app/src/main/java 2>/dev/null; then
        echo "  ❌ 说明声称已删除，但代码仍存在：$sym"
        grep -rn --include='*.java' "\b$sym\b" app/src/main/java 2>/dev/null | head -2 | sed 's/^/       /'
    else
        echo "  ✅ 已删除属实：$sym"
    fi
done

echo "$BODY" | grep -oP '新增\s+\K[a-zA-Z][a-zA-Z0-9_]{3,}(?=[\s，,）)]|$)' | sort -u | while read -r sym; do
    [ -z "$sym" ] && continue
    if grep -rq --include='*.java' "\b$sym\b" app/src/main/java 2>/dev/null; then
        echo "  ✅ 新增属实：$sym"
    else
        echo "  ❌ 说明声称已新增，但代码中找不到：$sym"
    fi
done

echo
echo "═══ 3. 签名 ═══"
APK=$(ls app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)
if [ -n "$APK" ]; then
    APKSIGNER="$ANDROID_HOME/build-tools/34.0.4/apksigner"
    if [ -x "$APKSIGNER" ]; then
        V2=$("$APKSIGNER" verify --print-certs -v "$APK" 2>&1 | grep -c 'v2 scheme.*true' || true)
        [ "$V2" -ge 1 ] && ok "v2 签名有效" || warn "未检出 v2 签名"
    else
        warn "apksigner 不存在，跳过签名校验"
    fi
    ok "release APK 存在：$(ls -lh "$APK" | awk '{print $5}')"
else
    warn "release APK 不存在（未构建或构建失败）"
fi

echo
echo "═══ 4. 遗留 TODO / 调试代码 ═══"
DBG=$(grep -rn --include='*.java' 'Log\.d("HyTerm"\|dbg("commit\|dbg("flush' app/src/main/java 2>/dev/null | wc -l)
[ "$DBG" -eq 0 ] && ok "无残留调试输出" || warn "发现 $DBG 处调试输出"

echo
if [ "$FAIL" -eq 0 ]; then
    echo "═══ ✅ 全部通过，可以发布 ═══"
    exit 0
else
    echo "═══ ❌ $FAIL 项未通过，禁止发布 ═══"
    exit 1
fi
