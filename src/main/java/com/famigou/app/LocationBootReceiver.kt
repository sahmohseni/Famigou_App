package com.famigou.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restores child tracking after reboot or an app update when a pairing exists. */
class LocationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val preferences = context.getSharedPreferences("kidora", Context.MODE_PRIVATE)
        if (preferences.getString("role", null) != "CHILD") return
        val connectionId = preferences.getString("connection_id", null) ?: return
        val accessToken = preferences.getString("access_token", null) ?: return
        val serviceIntent = Intent(context, LocationService::class.java)
            .putExtra(LocationService.EXTRA_CONNECTION_ID, connectionId)
            .putExtra(LocationService.EXTRA_ACCESS_TOKEN, accessToken)
        runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
    }
}
