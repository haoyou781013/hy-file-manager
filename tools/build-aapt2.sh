#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# 从源码构建 aarch64 版 aapt2（支持 API 35/36 资源）
#
# 背景：Google 从不发布 arm64 版 aapt2。现有 build-tools 里的 aapt2
# 全是 x86-64，唯一能跑的 34.0.4 aarch64 版本是 AndroidIDE 交叉编译的
# aapt2 2.19，无法解析 API 35+ 的新 resources.arsc（illegal map type 'string' 22）。
#
# 本脚本用 termux/android-build-tools 的 CMake 移植，本机编译出 2.20。
#
# 用法：sudo bash tools/build-aapt2.sh
# 产物：/opt/aapt2-build/build/vendor/aapt2 → 安装到 build-tools/34.0.4/
# ═══════════════════════════════════════════════════════════════
set -e

WORK=/opt/aapt2-build
TAG=16.0.0.4
MIRROR=https://mirrors.ustc.edu.cn/aosp   # android.googlesource.com 不可达，用 USTC
BUILD_TOOLS=/opt/toolchain/android-sdk/build-tools/34.0.4

echo "═══ 1. 依赖 ═══"
# 注意：proot 下 libc6/python3.12-minimal 的 postinst 会因 proroot-ldso 钩子失败，
# 需先把它们替换为 no-op 才能装其他包。这里假设已装好。
which clang-18 cmake ninja protoc bison flex || {
  echo "缺少工具链，请先执行："
  echo "  apt-get install -y clang-18 cmake ninja-build bison flex pkg-config \\"
  echo "      libfmt-dev libexpat1-dev libpng-dev libprotobuf-dev protobuf-compiler libgtest-dev"
  exit 1
}

echo "═══ 2. 拉源码 ═══"
if [ ! -d "$WORK" ]; then
  git clone --depth 1 --branch "$TAG" https://github.com/termux/android-build-tools.git "$WORK"
fi
cd "$WORK"

echo "═══ 3. 拉 AOSP 子仓库（11 个，顺序拉更稳）═══"
mkdir -p vendor && cd vendor
declare -A repos=(
  [build]="platform/build"
  [zopfli]="platform/external/zopfli"
  [base]="platform/frameworks/base"
  [native]="platform/frameworks/native"
  [core]="platform/system/core"
  [incremental_delivery]="platform/system/incremental_delivery"
  [libbase]="platform/system/libbase"
  [libziparchive]="platform/system/libziparchive"
  [logging]="platform/system/logging"
  [aidl]="platform/system/tools/aidl"
  [sysprop]="platform/system/tools/sysprop"
)
for name in build zopfli native core incremental_delivery libbase libziparchive logging aidl sysprop base; do
  [ -d "$name" ] && { echo "  跳过 $name"; continue; }
  echo -n "  $name ... "
  git clone -q --depth 1 --single-branch --branch android-16.0.0_r1 \
    "$MIRROR/${repos[$name]}.git" "$name" && echo "OK"
done
cd ..

echo "═══ 4. 修三处上游兼容性 ═══"
# 4a. CMakeLists 期待旧文件名 FlagNotEnabled*，AOSP 已改名 FlagDisabled*
sed -i 's/FlagNotEnabledResourceRemover/FlagDisabledResourceRemover/g' vendor/aapt2.cmake

# 4b. GCC 13 的 std::set 要求比较器可 const 调用
sed -i 's/bool operator()(const prop_info\& lhs, const prop_info\& rhs) {/bool operator()(const prop_info\& lhs, const prop_info\& rhs) const {/' \
  vendor/libbase/properties.cpp
sed -i 's/bool operator()(std::string_view lhs, const prop_info\& rhs) {/bool operator()(std::string_view lhs, const prop_info\& rhs) const {/' \
  vendor/libbase/properties.cpp
sed -i 's/bool operator()(const prop_info\& lhs, std::string_view rhs) {/bool operator()(const prop_info\& lhs, std::string_view rhs) const {/' \
  vendor/libbase/properties.cpp

# 4c. 新版 libstdc++ 移除了 tuple→pair 隐式转换
python3 - <<'PY'
p='vendor/base/tools/aapt2/ResourceTable.cpp'
s=open(p).read()
s=s.replace(
'''bool less_than_struct_with_name_and_id(const T& lhs,
                                       const std::pair<std::string_view, std::optional<U>>& rhs) {
  if (lhs.id != rhs.second) {
    return lhs.id < rhs.second;
  }
  return lhs.name < rhs.first;
}''',
'''bool less_than_struct_with_name_and_id(
    const T& lhs, const std::tuple<std::string_view, std::optional<U>>& rhs) {
  if (lhs.id != std::get<1>(rhs)) {
    return lhs.id < std::get<1>(rhs);
  }
  return lhs.name < std::get<0>(rhs);
}''')
open(p,'w').write(s)
PY

echo "═══ 5. CMake 配置 ═══"
# 必须用 clang：GCC 13 编译 LocaleDataLookup.cpp 会 ICE（GIMPLE pass: fre 段错误）
rm -rf build
cmake -B build -G Ninja \
  -DCMAKE_C_COMPILER=/usr/bin/clang-18 \
  -DCMAKE_CXX_COMPILER=/usr/bin/clang++-18 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="-Wno-invalid-constexpr -Wno-error" \
  -DCMAKE_CXX_FLAGS="-Wno-invalid-constexpr -Wno-error" \
  -DANDROID_BUILD_TOOLS_DEV_MODE=ON \
  -DANDROID_BUILD_TOOLS_PATCH_VENDOR=OFF   # 补丁已在上游，重复应用会失败

echo "═══ 6. 编译 ═══"
cmake --build build --target aapt2 -j4

echo "═══ 7. 验证 ═══"
A2=build/vendor/aapt2
"$A2" version
T=$(mktemp -d) && cd "$T"
printf '<?xml version="1.0" encoding="utf-8"?><manifest xmlns:android="http://schemas.android.com/apk/res/android" package="t.t"></manifest>' > M.xml
for v in 34 35 36; do
  echo -n "  android-$v: "
  "$OLDPWD/$A2" link -I "/opt/toolchain/android-sdk/platforms/android-$v/android.jar" \
    --manifest M.xml -o "o$v.apk" 2>&1 | head -1
  [ -f "o$v.apk" ] && echo "  ✅" || echo "  ❌"
done

echo "═══ 8. 安装 ═══"
cp "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/aapt2.219.bak" 2>/dev/null || true
cp "$OLDPWD/$A2" "$BUILD_TOOLS/aapt2"
chmod +x "$BUILD_TOOLS/aapt2"
echo "✅ 已安装到 $BUILD_TOOLS/aapt2"
