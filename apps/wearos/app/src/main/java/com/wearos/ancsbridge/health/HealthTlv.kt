package com.wearos.ancsbridge.health

/**
 * Health BLE codec — must match specs/health-ble-protocol.md byte-for-byte
 * (authoritative contract; pinned by apps/ios AncsBridgeTests/HealthTLVTests.swift).
 *
 * batch  = [u8 record_count] record+
 * record = [u8 type][u16 payload_len] payload
 * payload = [u64 record_id] type_specific_fields
 * All integers little-endian; timestamps are epoch milliseconds.
 */
object HealthTlv {

    // Record types
    const val TYPE_HEART_RATE = 1
    const val TYPE_STEPS = 2
    const val TYPE_SLEEP = 3
    const val TYPE_SLEEP_SUMMARY = 4
    const val TYPE_WORKOUT_HEADER = 5
    const val TYPE_WORKOUT_HR_STREAM = 6
    const val TYPE_WORKOUT_GPS = 7
    const val TYPE_BATTERY = 8

    // Sleep stages (contract value table)
    const val SLEEP_AWAKE = 0
    const val SLEEP_LIGHT = 1
    const val SLEEP_DEEP = 2
    const val SLEEP_REM = 3
    const val SLEEP_IN_BED = 4

    // Workout activities (contract value table); unmapped → OTHER
    const val ACTIVITY_RUNNING = 0
    const val ACTIVITY_WALKING = 1
    const val ACTIVITY_CYCLING = 2
    const val ACTIVITY_SWIMMING = 3
    const val ACTIVITY_STRENGTH = 4
    const val ACTIVITY_YOGA = 5
    const val ACTIVITY_HIKING = 6
    const val ACTIVITY_ELLIPTICAL = 7
    const val ACTIVITY_ROWING = 8
    const val ACTIVITY_HIIT = 9
    const val ACTIVITY_DANCE = 10
    const val ACTIVITY_BOXING = 11
    const val ACTIVITY_PILATES = 12
    const val ACTIVITY_GYMNASTICS = 13
    const val ACTIVITY_GOLF = 14
    const val ACTIVITY_TENNIS = 15
    const val ACTIVITY_BASKETBALL = 16
    const val ACTIVITY_SOCCER = 17
    const val ACTIVITY_RUGBY = 18
    const val ACTIVITY_CRICKET = 19
    const val ACTIVITY_SKIING = 20
    const val ACTIVITY_SNOWBOARDING = 21
    const val ACTIVITY_SKATING = 22
    const val ACTIVITY_CLIMBING = 23
    const val ACTIVITY_OTHER = 255

    // Control commands (…B5)
    const val CMD_HELLO = 0x01
    const val CMD_ACK = 0x02
    const val CMD_TIME_SYNC = 0x03
    const val CMD_PING = 0x04
    const val CMD_PONG = 0x05

    const val MAX_ACK_IDS = 63
    const val MAX_GPS_POINTS_PER_RECORD = 255
    const val MAX_HR_SAMPLES_PER_RECORD = 255

    /** Batch cap when MTU is unknown; effective cap = negotiated MTU − 3. */
    const val DEFAULT_MAX_BATCH_BYTES = 509
    const val MAX_RECORDS_PER_BATCH = 50

    /** v1 sends record types 1..8 → bitmap bits 0..7. */
    const val TYPE_BITMAP = 0xFF

    // ---- Wire records ------------------------------------------------------

    sealed class WireRecord {
        abstract val recordId: Long
        abstract val type: Int
    }

    data class HeartRate(override val recordId: Long, val bpm: Int, val startEpochMs: Long) : WireRecord() {
        override val type get() = TYPE_HEART_RATE
    }

    data class Steps(
        override val recordId: Long,
        val stepCount: Long,
        val startEpochMs: Long,
        val endEpochMs: Long
    ) : WireRecord() {
        override val type get() = TYPE_STEPS
    }

    data class SleepStage(
        override val recordId: Long,
        val stage: Int,
        val startEpochMs: Long,
        val endEpochMs: Long
    ) : WireRecord() {
        override val type get() = TYPE_SLEEP
    }

    data class SleepStageMinutes(val stage: Int, val minutes: Long)

    data class SleepSummary(
        override val recordId: Long,
        val startEpochMs: Long,
        val endEpochMs: Long,
        val stages: List<SleepStageMinutes>
    ) : WireRecord() {
        override val type get() = TYPE_SLEEP_SUMMARY
    }

    data class WorkoutHeader(
        override val recordId: Long,
        val activity: Int,
        val startEpochMs: Long,
        val endEpochMs: Long,
        val activeMinutes: Long,
        val caloriesTenths: Int,
        val distanceMeters: Long,
        val avgHrBpm: Int,
        val maxHrBpm: Int,
        val hrRecordCount: Int,
        val gpsRecordCount: Int
    ) : WireRecord() {
        override val type get() = TYPE_WORKOUT_HEADER
    }

    data class HrSample(val timestampMs: Long, val bpm: Int)

    data class WorkoutHrStream(
        override val recordId: Long,
        val samples: List<HrSample>
    ) : WireRecord() {
        override val type get() = TYPE_WORKOUT_HR_STREAM
    }

    data class GpsPoint(val latitudeE7: Int, val longitudeE7: Int, val altitudeMeters: Float, val timestampMs: Long)

    data class WorkoutGps(
        override val recordId: Long,
        val points: List<GpsPoint>
    ) : WireRecord() {
        override val type get() = TYPE_WORKOUT_GPS
    }

    data class Battery(
        override val recordId: Long,
        val percent: Int,
        val timestampMs: Long
    ) : WireRecord() {
        override val type get() = TYPE_BATTERY
    }

    // ---- Control messages --------------------------------------------------

    sealed class ControlMessage {
        data class Hello(val installNonce: Long, val typeBitmap: Int, val cursorRecordId: Long) : ControlMessage()
        data class Ack(val recordIds: List<Long>) : ControlMessage()
        data class TimeSync(val epochMs: Long) : ControlMessage()
        object Ping : ControlMessage()
        object Pong : ControlMessage()
    }

    // ---- Encoding ----------------------------------------------------------

    fun encodeRecord(record: WireRecord): ByteArray {
        val payload = encodePayload(record)
        val out = ByteWriter(3 + payload.size)
        out.u8(record.type)
        out.u16(payload.size)
        out.bytes(payload)
        return out.toByteArray()
    }

    private fun encodePayload(record: WireRecord): ByteArray = when (record) {
        is HeartRate -> ByteWriter(18).apply {
            u64(record.recordId); u16(record.bpm); u64(record.startEpochMs)
        }
        is Steps -> ByteWriter(28).apply {
            u64(record.recordId); u32(record.stepCount)
            u64(record.startEpochMs); u64(record.endEpochMs)
        }
        is SleepStage -> ByteWriter(25).apply {
            u64(record.recordId); u8(record.stage)
            u64(record.startEpochMs); u64(record.endEpochMs)
        }
        is SleepSummary -> ByteWriter(25 + record.stages.size * 5).apply {
            u64(record.recordId)
            u64(record.startEpochMs); u64(record.endEpochMs)
            u8(record.stages.size)
            record.stages.forEach {
                u8(it.stage); u32(it.minutes)
            }
        }
        is WorkoutHeader -> ByteWriter(43).apply {
            u64(record.recordId); u8(record.activity)
            u64(record.startEpochMs); u64(record.endEpochMs)
            u32(record.activeMinutes); u16(record.caloriesTenths); u32(record.distanceMeters)
            u16(record.avgHrBpm); u16(record.maxHrBpm)
            u16(record.hrRecordCount); u16(record.gpsRecordCount)
        }
        is WorkoutHrStream -> ByteWriter(9 + record.samples.size * 10).apply {
            u64(record.recordId)
            u8(record.samples.size)
            record.samples.forEach {
                u64(it.timestampMs); u16(it.bpm)
            }
        }
        is WorkoutGps -> ByteWriter(9 + record.points.size * 20).apply {
            u64(record.recordId)
            u8(record.points.size)
            record.points.forEach {
                i32(it.latitudeE7); i32(it.longitudeE7); f32(it.altitudeMeters); u64(it.timestampMs)
            }
        }
        is Battery -> ByteWriter(17).apply {
            u64(record.recordId); u8(record.percent); u64(record.timestampMs)
        }
    }.toByteArray()

    /**
     * Splits records into batches, each ≤ maxBatchBytes total. A single record
     * that alone exceeds the cap is still emitted as its own batch (v1 records
     * cannot exceed 509 B, but HR/GPS chunk records must be pre-chunked by the
     * producer to stay under it).
     */
    fun buildBatches(records: List<WireRecord>, maxBatchBytes: Int = DEFAULT_MAX_BATCH_BYTES): List<ByteArray> {
        val batches = mutableListOf<ByteArray>()
        var current = mutableListOf<WireRecord>()
        var currentSize = 1 // record_count byte

        for (record in records) {
            val encodedSize = 3 + encodedPayloadSize(record)
            if (current.isNotEmpty() &&
                (currentSize + encodedSize > maxBatchBytes || current.size >= MAX_RECORDS_PER_BATCH)) {
                batches += encodeBatch(current)
                current = mutableListOf()
                currentSize = 1
            }
            current += record
            currentSize += encodedSize
        }
        if (current.isNotEmpty()) batches += encodeBatch(current)
        return batches
    }

    /** Encodes ONE complete batch (public — the sync manager groups records per batch). */
    fun encodeBatch(records: List<WireRecord>): ByteArray {
        val out = ByteWriter(records.sumOf { 3 + encodedPayloadSize(it) } + 1)
        out.u8(records.size)
        records.forEach { out.bytes(encodeRecord(it)) }
        return out.toByteArray()
    }

    /** Total wire size of one record: 3 B header (type + len) + payload. */
    fun encodedSize(record: WireRecord): Int = 3 + encodedPayloadSize(record)

    private fun encodedPayloadSize(record: WireRecord): Int = when (record) {
        is HeartRate -> 18
        is Steps -> 28
        is SleepStage -> 25
        is SleepSummary -> 25 + record.stages.size * 5
        is WorkoutHeader -> 43
        is WorkoutHrStream -> 9 + record.samples.size * 10
        is WorkoutGps -> 9 + record.points.size * 20
        is Battery -> 17
    }

    // ---- Control encoding (watch → iOS) --------------------------------------

    fun hello(installNonce: Long, cursorRecordId: Long, typeBitmap: Int = TYPE_BITMAP): ByteArray =
        ByteWriter(14).apply {
            u8(CMD_HELLO); u32(installNonce); u8(typeBitmap); u64(cursorRecordId)
        }.toByteArray()

    fun ping(): ByteArray = byteArrayOf(CMD_PING.toByte())

    // ---- Decoding (unit tests + iOS-side mirror) -----------------------------

    fun decodeBatch(bytes: ByteArray): List<WireRecord> {
        val reader = ByteReader(bytes)
        val count = reader.u8()
        val records = mutableListOf<WireRecord>()
        repeat(count) {
            val type = reader.u8()
            val len = reader.u16()
            val payload = reader.bytes(len)
            records += decodePayload(type, ByteReader(payload))
        }
        if (reader.remaining() != 0) throw CodecException("trailing bytes in batch: ${reader.remaining()}")
        return records
    }

    private fun decodePayload(type: Int, r: ByteReader): WireRecord {
        val recordId = r.u64()
        return when (type) {
            TYPE_HEART_RATE -> HeartRate(recordId, r.u16(), r.u64())
            TYPE_STEPS -> Steps(recordId, r.u32().toLong(), r.u64(), r.u64())
            TYPE_SLEEP -> SleepStage(recordId, r.u8(), r.u64(), r.u64())
            TYPE_SLEEP_SUMMARY -> {
                val start = r.u64(); val end = r.u64()
                val stageCount = r.u8()
                val stages = (0 until stageCount).map { SleepStageMinutes(r.u8(), r.u32().toLong()) }
                SleepSummary(recordId, start, end, stages)
            }
            TYPE_WORKOUT_HEADER -> WorkoutHeader(
                recordId, r.u8(), r.u64(), r.u64(),
                r.u32().toLong(), r.u16(), r.u32().toLong(),
                r.u16(), r.u16(), r.u16(), r.u16()
            )
            TYPE_WORKOUT_HR_STREAM -> {
                val sampleCount = r.u8()
                WorkoutHrStream(recordId, (0 until sampleCount).map { HrSample(r.u64(), r.u16()) })
            }
            TYPE_WORKOUT_GPS -> {
                val pointCount = r.u8()
                WorkoutGps(recordId, (0 until pointCount).map { GpsPoint(r.i32(), r.i32(), r.f32(), r.u64()) })
            }
            TYPE_BATTERY -> Battery(recordId, r.u8(), r.u64())
            else -> throw CodecException("unknown record type $type")
        }.also { if (r.remaining() != 0) throw CodecException("trailing bytes in payload of type $type") }
    }

    fun decodeControl(bytes: ByteArray): ControlMessage {
        val r = ByteReader(bytes)
        return when (val cmd = r.u8()) {
            CMD_HELLO -> ControlMessage.Hello(r.u32(), r.u8(), r.u64())
            CMD_ACK -> {
                val count = r.u8()
                ControlMessage.Ack((0 until count).map { r.u64() })
            }
            CMD_TIME_SYNC -> ControlMessage.TimeSync(r.u64())
            CMD_PING -> ControlMessage.Ping
            CMD_PONG -> ControlMessage.Pong
            else -> throw CodecException("unknown control command $cmd")
        }.also { if (r.remaining() != 0) throw CodecException("trailing bytes in control message") }
    }

    class CodecException(message: String) : Exception(message)

    // ---- Little-endian byte helpers -----------------------------------------

    class ByteWriter(size: Int) {
        private val buf = ByteArray(size)
        private var pos = 0

        fun u8(v: Int) { buf[pos++] = v.toByte() }
        fun u16(v: Int) { buf[pos++] = (v and 0xFF).toByte(); buf[pos++] = ((v ushr 8) and 0xFF).toByte() }
        fun u32(v: Long) {
            buf[pos++] = (v and 0xFF).toByte(); buf[pos++] = ((v ushr 8) and 0xFF).toByte()
            buf[pos++] = ((v ushr 16) and 0xFF).toByte(); buf[pos++] = ((v ushr 24) and 0xFF).toByte()
        }
        fun u64(v: Long) { u32(v); u32(v ushr 32) }
        fun i32(v: Int) { u32(v.toLong() and 0xFFFFFFFFL) }
        fun f32(v: Float) { u32(java.lang.Float.floatToIntBits(v).toLong() and 0xFFFFFFFFL) }
        fun bytes(b: ByteArray) { b.copyInto(buf, pos); pos += b.size }

        fun toByteArray() = buf
    }

    class ByteReader(private val buf: ByteArray) {
        private var pos = 0
        fun u8(): Int {
            if (pos >= buf.size) throw CodecException("unexpected end of buffer")
            return buf[pos++].toInt() and 0xFF
        }
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
        fun u64(): Long = u32() or (u32() shl 32)
        fun i32(): Int = u32().toInt()
        fun f32(): Float = java.lang.Float.intBitsToFloat(u32().toInt())
        fun bytes(len: Int): ByteArray {
            if (pos + len > buf.size) throw CodecException("unexpected end of buffer")
            val out = buf.copyOfRange(pos, pos + len)
            pos += len
            return out
        }
        fun remaining(): Int = buf.size - pos
    }
}
