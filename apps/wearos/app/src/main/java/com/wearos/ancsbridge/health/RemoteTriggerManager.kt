package com.wearos.ancsbridge.health

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wearos.ancsbridge.AncsApplication
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.ble.BleConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote triggers (protocol §Remote triggers): the iPhone owns the trigger
 * list, pushes it on connect/edits via TRIGGER_LIST; the watch persists it and
 * executes ONLY stored entries by index when TRIGGER_FIRE arrives — never a
 * raw payload off the wire.
 *
 * Launch may be blocked by background-activity-launch restrictions; in that
 * case a one-tap notification with the launch PendingIntent is posted instead
 * and TRIGGER_RESULT reports BLOCKED_BY_SYSTEM.
 */
class RemoteTriggerManager(
    private val context: Context,
    private val ble: BleConnectionManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "RemoteTriggerManager"
        private const val PREFS = "health_bridge"
        private const val KEY_TRIGGERS = "remote_triggers"
        private const val FALLBACK_NOTIFICATION_ID = 9100
    }

    private val triggers = mutableListOf<HealthTlv.Trigger>()

    fun start() {
        loadTriggers()
        scope.launch {
            ble.healthControlEvents.collect { data ->
                handleControlMessage(data)
            }
        }
    }

    private fun handleControlMessage(data: ByteArray) {
        val message = try {
            HealthTlv.decodeControl(data)
        } catch (_: HealthTlv.CodecException) {
            return // malformed / non-trigger messages are logged by HealthSyncManager
        }
        when (message) {
            is HealthTlv.ControlMessage.TriggerList -> {
                triggers.apply {
                    clear()
                    addAll(message.triggers)
                }
                saveTriggers()
                Log.i(TAG, "Trigger list updated: ${triggers.size} triggers")
            }
            is HealthTlv.ControlMessage.TriggerFire -> fire(message.index)
            is HealthTlv.ControlMessage.AppListReq -> sendAppList()
            else -> Unit // health-sync messages handled elsewhere
        }
    }

    /**
     * APP_LIST_REQ: report the watch's launchable apps back as ordered
     * APP_LIST chunks (protocol §Watch app list). Query + encode on IO; the
     * chunk writes go through the serialized queue so they cannot reorder.
     */
    private fun sendAppList() {
        scope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val seen = mutableSetOf<String>()
            val apps = try {
                pm.queryIntentActivities(launcherIntent, 0)
                    .asSequence()
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .filter { (pkg, _) -> pkg != context.packageName && seen.add(pkg) }
                    .map { (pkg, label) -> HealthTlv.AppInfo(pkg, label.ifEmpty { pkg }) }
                    .sortedWith(compareBy { it.label.lowercase() })
                    .toList()
            } catch (e: Exception) {
                Log.w(TAG, "App list query failed: ${e.message}")
                emptyList()
            }
            val chunks = HealthTlv.appListChunks(apps)
            Log.i(TAG, "APP_LIST: ${apps.size} launchable apps in ${chunks.size} chunk(s)")
            chunks.forEach { chunk ->
                ble.enqueueWrite(AncsConstants.HEALTH_CONTROL_CHARACTERISTIC_UUID, chunk)
            }
        }
    }

    private fun fire(index: Int) {
        val trigger = triggers.firstOrNull { it.index == index }
        val result = if (trigger == null) {
            Log.w(TAG, "TRIGGER_FIRE for unknown index $index")
            HealthTlv.TRIGGER_RESULT_NO_SUCH_TRIGGER
        } else {
            execute(trigger)
        }
        ble.enqueueWrite(
            AncsConstants.HEALTH_CONTROL_CHARACTERISTIC_UUID,
            HealthTlv.triggerResult(index, result)
        )
    }

    private fun execute(trigger: HealthTlv.Trigger): Int {
        val intent = when (trigger.kind) {
            HealthTlv.TRIGGER_KIND_PACKAGE ->
                context.packageManager.getLaunchIntentForPackage(trigger.payload)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                } ?: run {
                    Log.e(TAG, "No launch intent for package ${trigger.payload}")
                    return HealthTlv.TRIGGER_RESULT_LAUNCH_FAILED
                }
            HealthTlv.TRIGGER_KIND_URI ->
                Intent(Intent.ACTION_VIEW, Uri.parse(trigger.payload)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            else -> {
                Log.e(TAG, "Unknown trigger kind ${trigger.kind}")
                return HealthTlv.TRIGGER_RESULT_LAUNCH_FAILED
            }
        }
        return try {
            context.startActivity(intent)
            Log.i(TAG, "Launched trigger ${trigger.index}: ${trigger.payload}")
            HealthTlv.TRIGGER_RESULT_OK
        } catch (e: SecurityException) {
            Log.w(TAG, "Launch blocked by system (${e.message}) — posting one-tap notification")
            postFallbackNotification(trigger, intent)
            HealthTlv.TRIGGER_RESULT_BLOCKED_BY_SYSTEM
        } catch (e: Exception) {
            // ActivityNotFoundException and friends: no handler / cannot start
            Log.w(TAG, "Launch failed: ${e.message}")
            HealthTlv.TRIGGER_RESULT_LAUNCH_FAILED
        }
    }

    private fun postFallbackNotification(trigger: HealthTlv.Trigger, intent: Intent) {
        val pending = PendingIntent.getActivity(
            context, trigger.index, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (trigger.kind == HealthTlv.TRIGGER_KIND_PACKAGE) "Open app" else "Open link"
        val notification = NotificationCompat.Builder(context, AncsApplication.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification_default)
            .setContentTitle(title)
            .setContentText(trigger.payload)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm?.notify(FALLBACK_NOTIFICATION_ID + trigger.index, notification)
    }

    // ---- Persistence --------------------------------------------------------

    private fun loadTriggers() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TRIGGERS, null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                triggers += HealthTlv.Trigger(
                    index = o.getInt("index"),
                    kind = o.getInt("kind"),
                    payload = o.getString("payload")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unparseable stored trigger list: ${e.message}")
            triggers.clear()
        }
    }

    private fun saveTriggers() {
        val array = JSONArray()
        triggers.forEach {
            array.put(JSONObject().apply {
                put("index", it.index)
                put("kind", it.kind)
                put("payload", it.payload)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TRIGGERS, array.toString()).apply()
    }
}
