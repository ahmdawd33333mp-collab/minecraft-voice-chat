package com.ahm.voicechat

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * AUTO-SENSE GAME.
 *
 * Polls UsageStatsManager for the foreground app. This is the only
 * supported way for a normal app to know another app is foreground.
 *
 * IMPORTANT, stated honestly in the UI as well:
 * - The app CANNOT grant usage access itself. The user must grant it in
 *   Settings > Apps > Special access > Usage access, which the app opens.
 * - Without that grant, hasUsageAccess() is false and auto-sense does
 *   nothing; manual Connect in MainActivity still works.
 * - Minecraft Bedrock's package is com.mojang.minecraftpe.
 */
object GameWatcher {
    const val MINECRAFT_PACKAGE = "com.mojang.minecraftpe"

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Returns true only if the given package was the most recently used app in the window. */
    fun isForegroundPackage(context: Context, pkg: String): Boolean {
        if (!hasUsageAccess(context)) return false
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // A short window: the last app used in the last 10 seconds.
        val stats = usage.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 10_000,
            now
        ) ?: return false
        val latest = stats.maxByOrNull { it.lastTimeUsed } ?: return false
        return latest.packageName == pkg
    }

    /** Returns true only if Minecraft was the most recently used app in the window. */
    fun isGameForeground(context: Context): Boolean =
        isForegroundPackage(context, MINECRAFT_PACKAGE)

    /** True when this app itself is foreground (user looking at it). */
    fun isAppForeground(context: Context): Boolean =
        isForegroundPackage(context, context.packageName)
}
