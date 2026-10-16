package com.wearos.ancsbridge.health

import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps Room rows ↔ wire records (HealthTlv.WireRecord).
 * metaJson carries type-specific fields so a single table serves all types.
 */
object HealthRecordMapper {

    fun toWire(entity: HealthRecordEntity): HealthTlv.WireRecord = when (entity.type) {
        HealthTlv.TYPE_HEART_RATE -> HealthTlv.HeartRate(
            entity.recordId,
            entity.value?.toInt() ?: 0,
            entity.startEpochMs
        )
        HealthTlv.TYPE_STEPS -> HealthTlv.Steps(
            entity.recordId,
            entity.value?.toLong() ?: 0L,
            entity.startEpochMs,
            entity.endEpochMs
        )
        HealthTlv.TYPE_SLEEP -> {
            val meta = JSONObject(entity.metaJson ?: "{}")
            HealthTlv.SleepStage(entity.recordId, meta.optInt("stage", HealthTlv.SLEEP_AWAKE), entity.startEpochMs, entity.endEpochMs)
        }
        HealthTlv.TYPE_SLEEP_SUMMARY -> {
            val meta = JSONObject(entity.metaJson ?: "{}")
            val stages = JSONArray(meta.optJSONArray("stages") ?: JSONArray())
            val minutes = (0 until stages.length()).map { i ->
                val s = stages.getJSONObject(i)
                HealthTlv.SleepStageMinutes(s.getInt("stage"), s.getLong("minutes"))
            }
            HealthTlv.SleepSummary(entity.recordId, entity.startEpochMs, entity.endEpochMs, minutes)
        }
        HealthTlv.TYPE_WORKOUT_HEADER -> {
            val meta = JSONObject(entity.metaJson ?: "{}")
            HealthTlv.WorkoutHeader(
                entity.recordId,
                meta.optInt("activity", HealthTlv.ACTIVITY_OTHER),
                entity.startEpochMs,
                entity.endEpochMs,
                meta.optLong("active_min", 0L),
                meta.optInt("calories_tenths", 0),
                meta.optLong("distance_m", 0L),
                meta.optInt("avg_hr", 0),
                meta.optInt("max_hr", 0),
                meta.optInt("hr_record_count", 0),
                meta.optInt("gps_record_count", 0)
            )
        }
        HealthTlv.TYPE_WORKOUT_HR_STREAM -> {
            val meta = JSONObject(entity.metaJson ?: "{}")
            val samples = JSONArray(meta.optJSONArray("samples") ?: JSONArray())
            HealthTlv.WorkoutHrStream(
                entity.recordId,
                (0 until samples.length()).map { i ->
                    val s = samples.getJSONObject(i)
                    HealthTlv.HrSample(s.getLong("ts"), s.getInt("bpm"))
                }
            )
        }
        HealthTlv.TYPE_WORKOUT_GPS -> {
            val meta = JSONObject(entity.metaJson ?: "{}")
            val points = JSONArray(meta.optJSONArray("points") ?: JSONArray())
            HealthTlv.WorkoutGps(
                entity.recordId,
                (0 until points.length()).map { i ->
                    val p = points.getJSONObject(i)
                    HealthTlv.GpsPoint(p.getInt("lat_e7"), p.getInt("lng_e7"), p.optDouble("alt_m", 0.0).toFloat(), p.getLong("ts"))
                }
            )
        }
        HealthTlv.TYPE_BATTERY -> HealthTlv.Battery(entity.recordId, entity.value?.toInt() ?: 0, entity.startEpochMs)
        else -> throw IllegalArgumentException("unknown record type ${entity.type}")
    }

    // ---- Insert helpers (collector → entity) --------------------------------

    fun heartRate(bpm: Int, startEpochMs: Long) = HealthRecordEntity(
        type = HealthTlv.TYPE_HEART_RATE,
        startEpochMs = startEpochMs,
        endEpochMs = startEpochMs,
        value = bpm.toDouble()
    )

    fun steps(count: Long, startEpochMs: Long, endEpochMs: Long) = HealthRecordEntity(
        type = HealthTlv.TYPE_STEPS,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        value = count.toDouble()
    )

    fun sleepStage(stage: Int, startEpochMs: Long, endEpochMs: Long) = HealthRecordEntity(
        type = HealthTlv.TYPE_SLEEP,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        metaJson = JSONObject().put("stage", stage).toString()
    )

    fun sleepSummary(startEpochMs: Long, endEpochMs: Long, stages: List<HealthTlv.SleepStageMinutes>) =
        HealthRecordEntity(
            type = HealthTlv.TYPE_SLEEP_SUMMARY,
            startEpochMs = startEpochMs,
            endEpochMs = endEpochMs,
            metaJson = JSONObject()
                .put(
                    "stages",
                    JSONArray().apply { stages.forEach { put(JSONObject().put("stage", it.stage).put("minutes", it.minutes)) } }
                )
                .toString()
        )

    fun battery(percent: Int, timestampMs: Long) = HealthRecordEntity(
        type = HealthTlv.TYPE_BATTERY,
        startEpochMs = timestampMs,
        endEpochMs = timestampMs,
        value = percent.toDouble()
    )

    fun workoutHeader(
        activity: Int,
        startEpochMs: Long,
        endEpochMs: Long,
        activeMinutes: Long,
        caloriesTenths: Int,
        distanceMeters: Long,
        avgHrBpm: Int,
        maxHrBpm: Int,
        hrRecordCount: Int,
        gpsRecordCount: Int
    ) = HealthRecordEntity(
        type = HealthTlv.TYPE_WORKOUT_HEADER,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        metaJson = JSONObject()
            .put("activity", activity)
            .put("active_min", activeMinutes)
            .put("calories_tenths", caloriesTenths)
            .put("distance_m", distanceMeters)
            .put("avg_hr", avgHrBpm)
            .put("max_hr", maxHrBpm)
            .put("hr_record_count", hrRecordCount)
            .put("gps_record_count", gpsRecordCount)
            .toString()
    )

    fun workoutHrStream(samples: List<HealthTlv.HrSample>) = HealthRecordEntity(
        type = HealthTlv.TYPE_WORKOUT_HR_STREAM,
        startEpochMs = samples.firstOrNull()?.timestampMs ?: 0L,
        endEpochMs = samples.lastOrNull()?.timestampMs ?: 0L,
        metaJson = JSONObject()
            .put("samples", JSONArray().apply {
                samples.forEach { put(JSONObject().put("ts", it.timestampMs).put("bpm", it.bpm)) }
            })
            .toString()
    )

    fun workoutGps(points: List<HealthTlv.GpsPoint>) = HealthRecordEntity(
        type = HealthTlv.TYPE_WORKOUT_GPS,
        startEpochMs = points.firstOrNull()?.timestampMs ?: 0L,
        endEpochMs = points.lastOrNull()?.timestampMs ?: 0L,
        metaJson = JSONObject()
            .put("points", JSONArray().apply {
                points.forEach {
                    put(
                        JSONObject()
                            .put("lat_e7", it.latitudeE7)
                            .put("lng_e7", it.longitudeE7)
                            .put("alt_m", it.altitudeMeters.toDouble())
                            .put("ts", it.timestampMs)
                    )
                }
            })
            .toString()
    )
}
