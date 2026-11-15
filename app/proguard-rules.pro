# ═══════════════════════════════════════════════════════════════
# HY 文件管理 R8 规则
# ═══════════════════════════════════════════════════════════════

# ── commons-compress 的可选依赖 ──
# 1.26 的 TarArchiveOutputStream 引用了 org.apache.commons.codec.Charsets
# （commons-codec 是 optional，我们不需要它），R8 会报 Missing class。
-dontwarn org.apache.commons.codec.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.slf4j.**

# ── 归档库：反射与序列化较多，保留全部 ──
-keep class org.apache.commons.compress.** { *; }
-keep class net.lingala.zip4j.** { *; }
-keep class com.github.junrar.** { *; }
-keep class org.tukaani.xz.** { *; }

# ── APK 图标解析（读取二进制 XML / resources.arsc，无反射但需保留结构）──
-keep class com.aliya.hyfilemgr.apkicon.** { *; }

# ── 崩溃处理：类名不能混淆，否则日志无法定位 ──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Android 组件（虽然用 R8 full mode 但显式声明更稳）──
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver

# ── View 的构造器（XML 膨胀 / 代码 new 都可能用到）──
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ── 自定义 View（NumberedEditText 等）──
-keep class com.aliya.hyfilemgr.widget.** { *; }

# ── Parcelable / Serializable 保持字段 ──
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── commons-compress 的 pack200 模块依赖 ASM，我们完全不用 ──
-dontwarn org.objectweb.asm.**
-dontwarn org.apache.commons.compress.harmony.**

# 直接裁掉整个 harmony 包（pack200 / 类文件解析，与压缩包处理无关）
-dontwarn org.apache.commons.compress.harmony.pack200.**

# ═══════════════════════════════════════════════════════════════
# 剥掉调试日志
#
# 为什么：Log.d/Log.v 在 release 包里仍会执行字符串拼接（热路径上
# 每次进压缩包都要拼 p.path + inner + items.size()），且 p.path 是
# 用户真实文件路径，logcat 任何有日志权限的应用都能读。
#
# 注意：Log.w / Log.e 必须保留（崩溃排查与故障诊断依赖它们）。
# ═══════════════════════════════════════════════════════════════
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static boolean isLoggable(java.lang.String, int);
}
