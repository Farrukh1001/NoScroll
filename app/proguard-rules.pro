# NoScroll release ProGuard/R8 rules.
#
# The app has no reflection-based dependency injection, no serialisation library, and no dynamic
# class loading, so the default rules cover almost everything. What is listed below is what R8
# cannot see is used, because the platform instantiates it by name from the manifest.

# Components named in AndroidManifest.xml are constructed reflectively by the framework.
-keep class com.noscroll.NoScrollApplication { *; }
-keep class com.noscroll.service.accessibility.NoScrollAccessibilityService { *; }
-keep class com.noscroll.service.vpn.NoScrollVpnService { *; }
-keep class com.noscroll.service.BootReceiver { *; }
-keep class com.noscroll.service.PackageStateReceiver { *; }
-keep class com.noscroll.ui.MainActivity { *; }
-keep class com.noscroll.ui.shorts.ShortsLinkActivity { *; }

# WorkManager instantiates workers by class name.
-keep class com.noscroll.work.MaintenanceWorker { *; }
-keep class com.noscroll.work.EveningSummaryWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Enum names are persisted as setting ids and read back; obfuscating valueOf breaks stored state.
-keepclassmembers enum com.noscroll.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep the setting keys themselves — their ids are the on-disk schema.
-keep class com.noscroll.domain.protection.SettingKey { *; }

# Line numbers make a crash report from a release build actionable. The source file name is
# renamed away, so this leaks nothing about the code layout.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin coroutines: the default consumer rules cover this, but the debug-probe class is only
# referenced reflectively.
-dontwarn kotlinx.coroutines.debug.**
