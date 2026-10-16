package com.wearos.ancsbridge.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Reads sleep sessions and workouts from on-watch Health Connect (present on
 * Wear OS 5+; OHealth and the system fitness apps publish there) and turns them
 * into health_records for the BLE bridge.
 *
 * Sleep and workouts are NOT available via passive Health Services (no such
 * data types in health-services-client 1.1.0) — this reader is their only source.
 *
 * Polls every 30 min over a rolling 7-day window; SeenHcDao dedupes re-reads
 * by Health Connect record id. Workouts are emitted as WORKOUT_HEADER followed
 * by WORKOUT_HR_STREAM / WORKOUT_GPS chunk records, with the header carrying
 * the chunk counts (specs/health-ble-protocol.md completion detection).
 *
 * Health Connect read permissions (READ_SLEEP, READ_EXERCISE, READ_HEART_RATE,
 * READ_DISTANCE, READ_TOTAL_CALORIES_BURNED) are requested in MainActivity.
 */
class HealthConnectReader(
    private val context: Context,
    private val dao: HealthDao,
    private val seenDao: SeenHcDao,
    private val scope: CoroutineScope,
    private val onRecordsAvailable: () -> Unit
) {

    companion object {
        private const val TAG = "HealthConnectReader"
        private const val POLL_INTERVAL_MS = 30 * 60 * 1000L
        private const val WINDOW_DAYS = 7L
        // Chunk caps so one record fits a 509 B batch:
        // record = 3 B TLV header + payload; payload = 9 B (u64 id + u8 count) + per-item bytes
        private const val HR_SAMPLES_PER_CHUNK = 40   // 3 + 9 + 10n ≤ 509 → n ≤ 49; 40 is safe
        private const val GPS_POINTS_PER_CHUNK = 24   // 3 + 9 + 20n ≤ 509 → n ≤ 24

        // Health Connect SleepSessionRecord.Stage stage ints (StageTypes is
        // @RestrictTo so the constants aren't public API — values per docs):
        // 0 UNKNOWN, 1 AWAKE, 2 AWAKE_IN_BED, 3 SLEEPING, 4 OUT_OF_BED,
        // 5 LIGHT, 6 DEEP, 7 REM. TODO(T7): verify on hardware.
        private const val HC_STAGE_UNKNOWN = 0
        private const val HC_STAGE_AWAKE = 1
        private const val HC_STAGE_AWAKE_IN_BED = 2
        private const val HC_STAGE_SLEEPING = 3
        private const val HC_STAGE_OUT_OF_BED = 4
        private const val HC_STAGE_LIGHT = 5
        private const val HC_STAGE_DEEP = 6
        private const val HC_STAGE_REM = 7

        /** Platform permission string (ExerciseRoute is not a Record, so HealthPermission
         *  has no getReadPermission overload for it; the manifest declares the same string). */
        private const val READ_EXERCISE_ROUTE_PERMISSION =
            "android.permission.health.READ_EXERCISE_ROUTE"
    }

    private var pollJob: Job? = null
    private var client: HealthConnectClient? = null

    private val requiredPermissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        // ExerciseRoute is not a Record (no getReadPermission overload) — use the
        // platform permission string the manifest already declares.
        READ_EXERCISE_ROUTE_PERMISSION,
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
    )

    fun start() {
        pollJob = scope.launch {
            while (isActive) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    Log.w(TAG, "Health Connect poll failed: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollOnce() {
        // sdkStatus is internal-API in connect-client 1.1.0 (Kotlin refuses it);
        // getOrCreate throws when the provider is unavailable, which runCatching turns
        // into a null client — same effect, supported surface only.
        val hc = client ?: runCatching { HealthConnectClient.getOrCreate(context) }
            .getOrNull()?.also { client = it }
        if (hc == null) {
            Log.i(TAG, "Health Connect unavailable on this device — sleep/workouts not collected")
            return
        }
        if (!hc.permissionController.getGrantedPermissions().containsAll(requiredPermissions)) {
            Log.i(TAG, "Health Connect permissions not granted — sleep/workouts not collected")
            return
        }

        val windowStart = Instant.now().minusSeconds(WINDOW_DAYS * 24 * 3600)
        val filter = TimeRangeFilter.between(windowStart, Instant.now())
        val seen = seenDao.allIds().toMutableSet()
        val newEntities = mutableListOf<HealthRecordEntity>()
        val newSeenIds = mutableListOf<SeenHcRecord>()

        // ---- Sleep ----------------------------------------------------------
        val sleepRecords = hc.readRecords(ReadRecordsRequest<SleepSessionRecord>(filter)).records
        for (session in sleepRecords) {
            if (session.metadata.id in seen) continue
            val stages = session.stages.mapNotNull { stage ->
                mapSleepStage(stage.stage)?.let { mapped ->
                    HealthTlv.SleepStage(
                        recordId = 0, // Room assigns; ids only matter for wire records built later
                        stage = mapped,
                        startEpochMs = stage.startTime.toEpochMilli(),
                        endEpochMs = stage.endTime.toEpochMilli()
                    )
                }
            }
            stages.forEach { newEntities += HealthRecordMapper.sleepStage(it.stage, it.startEpochMs, it.endEpochMs) }
            newEntities += HealthRecordMapper.sleepSummary(
                session.startTime.toEpochMilli(),
                session.endTime.toEpochMilli(),
                stages.groupBy({ it.stage }).map { (stage, list) ->
                    HealthTlv.SleepStageMinutes(
                        stage,
                        list.sumOf { (it.endEpochMs - it.startEpochMs) / 60_000L }
                    )
                }
            )
            newSeenIds += SeenHcRecord(session.metadata.id)
        }

        // ---- Workouts ---------------------------------------------------------
        val workouts = hc.readRecords(ReadRecordsRequest<ExerciseSessionRecord>(filter)).records
        for (session in workouts) {
            if (session.metadata.id in seen) continue
            newEntities += buildWorkoutRecords(hc, session)
            newSeenIds += SeenHcRecord(session.metadata.id)
        }

        if (newEntities.isNotEmpty() || newSeenIds.isNotEmpty()) {
            if (newSeenIds.isNotEmpty()) seenDao.insertAll(newSeenIds)
            if (newEntities.isNotEmpty()) {
                dao.insertAll(newEntities)
                Log.i(TAG, "Imported ${newEntities.size} records from Health Connect")
                onRecordsAvailable()
            }
        }
    }

    private suspend fun buildWorkoutRecords(
        hc: HealthConnectClient,
        session: ExerciseSessionRecord
    ): List<HealthRecordEntity> {
        val startMs = session.startTime.toEpochMilli()
        val endMs = session.endTime.toEpochMilli()
        val window = TimeRangeFilter.between(session.startTime, session.endTime)

        // HR samples during the session
        val hrSamples = hc.readRecords(ReadRecordsRequest<HeartRateRecord>(window)).records
            .flatMap { it.samples }
            .sortedBy { it.time.toEpochMilli() }
            .map { HealthTlv.HrSample(it.time.toEpochMilli(), it.beatsPerMinute.toInt()) }

        // GPS route (requires READ_EXERCISE_ROUTE permission + user consent on some builds)
        val gpsPoints = (session.exerciseRouteResult as? ExerciseRouteResult.Data)
            ?.exerciseRoute?.route
            ?.map {
                HealthTlv.GpsPoint(
                    (it.latitude * 1e7).toInt(),
                    (it.longitude * 1e7).toInt(),
                    it.altitude?.inMeters?.toFloat() ?: 0f,
                    it.time.toEpochMilli()
                )
            }
            ?: emptyList()

        val hrChunks = hrSamples.chunked(HR_SAMPLES_PER_CHUNK)
        val gpsChunks = gpsPoints.chunked(GPS_POINTS_PER_CHUNK)

        val avgHr = hrSamples.takeIf { it.isNotEmpty() }?.let { list -> list.sumOf { it.bpm } / list.size } ?: 0
        val maxHr = hrSamples.maxOfOrNull { it.bpm } ?: 0

        val distanceMeters = hc.readRecords(ReadRecordsRequest<DistanceRecord>(window)).records
            .sumOf { it.distance.inMeters.toDouble() }.toLong()
        val caloriesKcal = hc.readRecords(ReadRecordsRequest<TotalCaloriesBurnedRecord>(window)).records
            .sumOf { it.energy.inKilocalories }

        val entities = mutableListOf<HealthRecordEntity>()
        entities += HealthRecordMapper.workoutHeader(
            activity = mapExerciseType(session.exerciseType),
            startEpochMs = startMs,
            endEpochMs = endMs,
            activeMinutes = (endMs - startMs) / 60_000L,
            caloriesTenths = (caloriesKcal * 10).toInt(),
            distanceMeters = distanceMeters,
            avgHrBpm = avgHr,
            maxHrBpm = maxHr,
            hrRecordCount = hrChunks.size,
            gpsRecordCount = gpsChunks.size
        )
        hrChunks.forEach { entities += HealthRecordMapper.workoutHrStream(it) }
        gpsChunks.forEach { entities += HealthRecordMapper.workoutGps(it) }
        return entities
    }

    /** null = skip the record entirely (UNKNOWN stage carries no useful signal). */
    private fun mapSleepStage(hcStage: Int): Int? = when (hcStage) {
        HC_STAGE_AWAKE, HC_STAGE_AWAKE_IN_BED, HC_STAGE_OUT_OF_BED -> HealthTlv.SLEEP_AWAKE
        HC_STAGE_SLEEPING, HC_STAGE_LIGHT -> HealthTlv.SLEEP_LIGHT
        HC_STAGE_DEEP -> HealthTlv.SLEEP_DEEP
        HC_STAGE_REM -> HealthTlv.SLEEP_REM
        else -> null
    }

    private fun mapExerciseType(hcType: Int): Int = when (hcType) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> HealthTlv.ACTIVITY_RUNNING
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> HealthTlv.ACTIVITY_WALKING
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> HealthTlv.ACTIVITY_CYCLING
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> HealthTlv.ACTIVITY_SWIMMING
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> HealthTlv.ACTIVITY_STRENGTH
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> HealthTlv.ACTIVITY_YOGA
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> HealthTlv.ACTIVITY_HIKING
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> HealthTlv.ACTIVITY_ELLIPTICAL
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING, ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> HealthTlv.ACTIVITY_ROWING
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> HealthTlv.ACTIVITY_HIIT
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> HealthTlv.ACTIVITY_DANCE
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> HealthTlv.ACTIVITY_BOXING
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> HealthTlv.ACTIVITY_PILATES
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS -> HealthTlv.ACTIVITY_GYMNASTICS
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> HealthTlv.ACTIVITY_GOLF
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> HealthTlv.ACTIVITY_TENNIS
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> HealthTlv.ACTIVITY_BASKETBALL
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER, ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN -> HealthTlv.ACTIVITY_SOCCER
        ExerciseSessionRecord.EXERCISE_TYPE_RUGBY -> HealthTlv.ACTIVITY_RUGBY
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET -> HealthTlv.ACTIVITY_CRICKET
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> HealthTlv.ACTIVITY_SKIING
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> HealthTlv.ACTIVITY_SNOWBOARDING
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING, ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING -> HealthTlv.ACTIVITY_SKATING
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> HealthTlv.ACTIVITY_CLIMBING
        else -> HealthTlv.ACTIVITY_OTHER
    }
}
