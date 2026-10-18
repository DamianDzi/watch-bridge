package com.wearos.ancsbridge.ancs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts AncsService after device boot if a bonded iPhone exists, and
 * after the app is updated (Android does not restart sticky services on
 * package replace — without this the watch side sat dead after every
 * `adb install -r`).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val isBoot = intent.action == Intent.ACTION_BOOT_COMPLETED
        val isSelfReplaced = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (isBoot || isSelfReplaced) {
            Log.i(TAG, "Starting AncsService (${intent.action})")
            val serviceIntent = Intent(context, AncsService::class.java).apply {
                action = AncsService.ACTION_RECONNECT
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
