package com.robbydock.scene

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Clears passcodes, recovery codes and lockouts. Profiles and settings stay. Requires the DUMP
 * permission.
 */
class AdminReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_CLEAR) return
        Profiles.load(ctx)
        Profiles.clearSecrets(ctx)
        Log.i("Scene", "Passcodes, recovery codes and lockouts cleared by adb")
    }

    companion object {
        const val ACTION_CLEAR = "com.robbydock.scene.CLEAR_PASSCODES"
    }
}
