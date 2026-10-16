package com.wearos.ancsbridge.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.awaitWithException
import androidx.health.services.client.data.DataPoint
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.SampleDataPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
                passiveClient.setPassiveListenerCallback(config, directExecutor, callback).awaitWithException()
                Log.i(TAG, "Passive monitoring registered for HR + steps")
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
            val entities = dataPoints.dataPoints.mapNotNull { it.toEntity() }
            if (entities.isEmpty()) return
            scope.launch {
                dao.insertAll(entities)
                Log.d(TAG, "Collected ${entities.size} health records")
                onRecordsAvailable()
            }
        }
    }

    private fun DataPoint.toEntity(): HealthRecordEntity? {
        // DataType does not override equals() in health-services-client 1.1.0
        // (verified against the AAR), so `when (dataType)` would be identity
        // comparison — compare by name instead.
        return when (this) {
            is SampleDataPoint<*> -> when (dataType.name) {
                DataType.HEART_RATE_BPM.name -> {
                    val bpm = (value as? Long)?.toInt() ?: return null
                    HealthRecordMapper.heartRate(bpm, timeInstant.toEpochMilli())
                }
                else -> null
            }
            is IntervalDataPoint<*> -> when (dataType.name) {
                DataType.STEPS.name -> {
                    val count = (value as? Long) ?: return null
                    HealthRecordMapper.steps(
                        count,
                        startInstant.toEpochMilli(),
                        endInstant.toEpochMilli()
                    )
                }
                else -> null
            }
            else -> null
        }
    }
}
