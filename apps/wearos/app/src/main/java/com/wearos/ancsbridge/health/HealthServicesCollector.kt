package com.wearos.ancsbridge.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.PassiveListenerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Battery-conscious collection via Wear OS Health Services
 * (health-services-client 1.1.0), plus battery level changes. Everything
 * lands in Room; HealthSyncManager delivers it to the iPhone.
 *
 * Roadmap B7: the passive listener requests STEPS only (cheap interval
 * data). Heart rate is polled on a 5-minute cycle via MeasureClient —
 * register, take the first sample, unregister — instead of streaming at
 * OEM density (~1/s, sensor hot 24/7). Apple Health rest HR needs
 * minutes-resolution, not seconds; dense workout-time HR arrives via the
 * Health Connect ExerciseSessionRecord path (see HealthConnectReader).
 * Sleep/workout sessions are NOT available through passive Health
 * Services (no such data types in 1.1.0) — those also come from
 * HealthConnectReader.
 *
 * BODY_SENSORS / ACTIVITY_RECOGNITION runtime permissions are requested
 * in MainActivity.
 */
class HealthServicesCollector(
    private val context: Context,
    private val dao: HealthDao,
    private val scope: CoroutineScope,
    private val onRecordsAvailable: () -> Unit
) {

    companion object {
        private const val TAG = "HealthCollector"

        // HR poll cadence (B7): 5 min. Trade-off: rest HR in Apple Health
        // at minute-scale cadence with the sensor hot for only ~seconds per
        // cycle, vs. ~86k samples/day with continuous passive streaming.
        private const val HR_POLL_INTERVAL_MS = 300_000L

        // First poll shortly after start so a fresh install shows HR quickly.
        private const val HR_POLL_INITIAL_DELAY_MS = 15_000L

        // How long one poll waits for a sample before giving up (off-wrist,
        // sensor unavailable). No retry storm — the next cycle tries again.
        private const val HR_POLL_WINDOW_MS = 20_000L

        // Safety valve for the Room buffer: unsent records older than this
        // are dropped so a long-dead link can't grow the DB forever.
        private const val RECORD_RETENTION_MS = 14L * 24 * 60 * 60 * 1000
    }

    private val healthClient = HealthServices.getClient(context)
    private val passiveClient = healthClient.passiveMonitoringClient
    private val directExecutor = java.util.concurrent.Executor { it.run() }

    private var lastBatteryPercent = -1
    private var lastBatteryRecordMs = 0L
    private var hrPollJob: Job? = null

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
                    .setDataTypes(setOf(DataType.STEPS))
                    .setShouldUserActivityInfoBeRequested(false)
                    .build()
                // setPassiveListenerCallback(config, executor, callback) is void in
                // health-services-client 1.1.0 — registration failures arrive via
                // callback.onRegistrationFailed instead.
                passiveClient.setPassiveListenerCallback(config, directExecutor, callback)
                Log.i(TAG, "Passive monitoring registration requested for steps (HR is polled)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register passive monitoring: ${e.message}")
            }
        }
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        hrPollJob = scope.launch {
            delay(HR_POLL_INITIAL_DELAY_MS)
            while (true) {
                pollHeartRateOnce()
                delay(HR_POLL_INTERVAL_MS)
            }
        }

        // One-shot prune on start (service restarts are rare; cheap query).
        scope.launch {
            val cutoff = System.currentTimeMillis() - RECORD_RETENTION_MS
            val pruned = dao.deleteOlderThan(cutoff)
            if (pruned > 0) Log.i(TAG, "Pruned $pruned health records older than 14 days")
        }
    }

    fun stop() {
        hrPollJob?.cancel()
        hrPollJob = null
        // Synchronous: the service scope is about to be cancelled, so a
        // scope.launch'd clear would never run.
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

    /**
     * One HR sample: register a MeasureCallback, take the first HEART_RATE_BPM
     * data point (or bail on unavailability / window timeout), unregister.
     * measureClient.registerMeasureCallback is void in 1.1.0 — failures arrive
     * via onRegistrationFailed.
     */
    private suspend fun pollHeartRateOnce() {
        val measureClient = healthClient.measureClient
        val sample = CompletableDeferred<Pair<Int, Long>?>() // bpm to wall-clock ms

        val measureCallback = object : MeasureCallback {
            override fun onAvailabilityChanged(
                dataType: androidx.health.services.client.data.DeltaDataType<*, *>,
                availability: androidx.health.services.client.data.Availability
            ) {
                if (availability !is DataTypeAvailability ||
                    (availability != DataTypeAvailability.AVAILABLE &&
                        availability != DataTypeAvailability.ACQUIRING)
                ) {
                    // UNAVAILABLE / UNAVAILABLE_DEVICE_OFF_BODY / etc. — no
                    // point waiting the full window.
                    Log.d(TAG, "HR poll: sensor unavailable ($availability) — skipping")
                    sample.complete(null)
                }
            }

            override fun onDataReceived(dataPoints: DataPointContainer) {
                val bootInstant = Instant.now().minusMillis(SystemClock.elapsedRealtime())
                dataPoints.getData(DataType.HEART_RATE_BPM).firstOrNull()?.let { p ->
                    sample.complete(Pair(p.value.toInt(), p.getTimeInstant(bootInstant).toEpochMilli()))
                }
            }

            override fun onRegistrationFailed(throwable: Throwable) {
                Log.d(TAG, "HR poll registration failed: ${throwable.message}")
                sample.complete(null)
            }
        }

        try {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, directExecutor, measureCallback)
        } catch (e: Exception) {
            Log.d(TAG, "HR poll: register threw ${e.message}")
            return
        }

        try {
            val result = withTimeoutOrNull(HR_POLL_WINDOW_MS) { sample.await() }
            if (result != null) {
                dao.insert(HealthRecordMapper.heartRate(result.first, result.second))
                onRecordsAvailable()
            }
        } finally {
            try {
                // Fire-and-forget future; failure is benign (callback already dead).
                measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, measureCallback)
            } catch (_: Exception) {
            }
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
            // Passive config requests STEPS only, but keep the HR branch for
            // OEMs that push extra types — harmless if empty.
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
