package com.wearos.ancsbridge.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.PassiveListenerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Continuous passive collection of heart-rate samples and step intervals via
 * Wear OS Health Services (health-services-client 1.1.0), plus battery level
 * changes. Everything lands in Room; HealthSyncManager delivers it to the iPhone.
 *
 * Note: sleep and workout sessions are NOT available through passive Health
 * Services (no such data types in health-services-client 1.1.0) — those come
 * from HealthConnectReader instead.
 *
 * BODY_SENSORS / ACTIVITY_RECOGNITION runtime permissions are requested in
 * MainActivity; passive HR availability is OEM-dependent (OPW3: verify in T7).
 */
class HealthServicesCollector(
    private val context: Context,
    private val dao: HealthDao,
    private val scope: CoroutineScope,
    private val onRecordsAvailable: () -> Unit
) {

    companion object {
        private const val TAG = "HealthCollector"
    }

    private val passiveClient = HealthServices.getClient(context).passiveMonitoringClient
    private val directExecutor = java.util.concurrent.Executor { it.run() }

    private var lastBatteryPercent = -1
    private var lastBatteryRecordMs = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val percent = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (percent < 0 || scale <= 0) return
            val pct = percent * 100 / scale
            val now = System.currentTimeMillis()
            if (pct != lastBatteryPercent || now - lastBatteryRecordMs > 3_600_000L) {
                lastBatteryPercent = pct
                lastBatteryRecordMs = now
                scope.launch {
                    dao.insert(HealthRecordMapper.battery(pct, now))
                    onRecordsAvailable()
                }
            }
        }
    }

    fun start() {
        scope.launch {
            try {
                val config = PassiveListenerConfig.builder()
                    .setDataTypes(setOf(DataType.HEART_RATE_BPM, DataType.STEPS))
                    .setShouldUserActivityInfoBeRequested(false)
                    .build()
                // setPassiveListenerCallback(config, executor, callback) is void in
                // health-services-client 1.1.0 — registration failures arrive via
                // callback.onRegistrationFailed instead.
                passiveClient.setPassiveListenerCallback(config, directExecutor, callback)
                Log.i(TAG, "Passive monitoring registration requested for HR + steps")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register passive monitoring: ${e.message}")
            }
        }
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun stop() {
        // Synchronous: the service scope is about to be cancelled, so a
        // scope.launch'd clear would never run (LOW-1).
        try {
            passiveClient.clearPassiveListenerCallbackAsync()
        } catch (e: Exception) {
            Log.w(TAG, "clearPassiveListenerCallbackAsync failed: ${e.message}")
        }
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private val callback = object : PassiveListenerCallback {
        override fun onRegistered() {
            Log.i(TAG, "Passive listener registered")
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Passive listener registration failed: ${throwable.message}")
        }

        override fun onPermissionLost() {
            Log.e(TAG, "Health Services permission lost — regrant BODY_SENSORS/ACTIVITY_RECOGNITION")
        }

        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            // DataPointContainer's dataPoints map is internal; the typed getData()
            // accessors are the public surface. Data points carry durations-from-boot,
            // converted to wall-clock via the boot instant (now − elapsedRealtime).
            // The converters use Java-style names (getTimeInstant/getStartInstant/
            // getEndInstant) in health-services-client 1.1.0.
            val bootInstant = Instant.now().minusMillis(SystemClock.elapsedRealtime())
            val entities = mutableListOf<HealthRecordEntity>()
            dataPoints.getData(DataType.HEART_RATE_BPM).forEach { p ->
                entities += HealthRecordMapper.heartRate(
                    p.value.toInt(),
                    p.getTimeInstant(bootInstant).toEpochMilli()
                )
            }
            dataPoints.getData(DataType.STEPS).forEach { p ->
                entities += HealthRecordMapper.steps(
                    p.value,
                    p.getStartInstant(bootInstant).toEpochMilli(),
                    p.getEndInstant(bootInstant).toEpochMilli()
                )
            }
            if (entities.isEmpty()) return
            scope.launch {
                dao.insertAll(entities)
                Log.d(TAG, "Collected ${entities.size} health records")
                onRecordsAvailable()
            }
        }
    }
}
