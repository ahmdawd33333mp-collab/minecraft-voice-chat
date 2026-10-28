package com.ahm.voicechat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * After a reboot, restart the voice service if auto-start is on, so
 * opening Minecraft auto-connects without opening this app first.
 * Android may still block background starts on some devices; opening
 * the app once always starts the service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("voicechat", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autosense", true)) return
        val serviceIntent = Intent(context, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
