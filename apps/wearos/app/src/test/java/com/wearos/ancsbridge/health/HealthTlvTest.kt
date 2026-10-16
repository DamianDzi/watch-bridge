package com.wearos.ancsbridge.health

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins specs/health-ble-protocol.md byte-for-byte. The iOS mirror is
 * apps/ios/AncsBridgeTests/HealthTLVTests.swift — keep the golden vectors in sync.
 */
class HealthTlvTest {

    private fun le64(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 32) and 0xFF).toByte(), ((v ushr 40) and 0xFF).toByte(), ((v ushr 48) and 0xFF).toByte(), ((v ushr 56) and 0xFF).toByte()
    )

    private fun le32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte()
    )

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    // ---- Record golden vectors ----------------------------------------------

    @Test
    fun heartRateGoldenBytes() {
        val bytes = HealthTlv.encodeRecord(HealthTlv.HeartRate(recordId = 0x0102030405060708L, bpm = 0x1234, startEpochMs = 1_700_000_000_123L))
        // [type=1][len=18][id u64][bpm u16][start u64]
        val expected = byteArrayOf(1) + le16(18) + le64(0x0102030405060708L) + le16(0x1234) + le64(1_700_000_000_123L)
        assertArrayEquals(expected, bytes)
        assertEquals(HealthTlv.HeartRate(0x0102030405060708L, 0x1234, 1_700_000_000_123L), HealthTlv.decodeBatch(byteArrayOf(1) + bytes).single())
    }

    @Test
    fun stepsGoldenBytes() {
        val bytes = HealthTlv.encodeRecord(
            HealthTlv.Steps(recordId = 42L, stepCount = 1234L, startEpochMs = 1000L, endEpochMs = 2000L)
        )
        val expected = byteArrayOf(2) + le16(28) + le64(42) + le32(1234) + le64(1000) + le64(2000)
        assertArrayEquals(expected, bytes)
        assertEquals(
            HealthTlv.Steps(42L, 1234L, 1000L, 2000L),
            HealthTlv.decodeBatch(byteArrayOf(1) + bytes).single()
        )
    }

    @Test
    fun sleepStageGoldenBytes() {
        val bytes = HealthTlv.encodeRecord(
            HealthTlv.SleepStage(recordId = 7L, stage = HealthTlv.SLEEP_REM, startEpochMs = 10L, endEpochMs = 20L)
        )
        val expected = byteArrayOf(3) + le16(25) + le64(7) + byteArrayOf(3) + le64(10) + le64(20)
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun sleepSummaryGoldenBytes() {
        val record = HealthTlv.SleepSummary(
            recordId = 9L, startEpochMs = 0L, endEpochMs = 60_000L,
            stages = listOf(
                HealthTlv.SleepStageMinutes(HealthTlv.SLEEP_LIGHT, 120),
                HealthTlv.SleepStageMinutes(HealthTlv.SLEEP_DEEP, 60)
            )
        )
        val bytes = HealthTlv.encodeRecord(record)
        // 25 + 2*5 = 35 payload
        val expected = byteArrayOf(4) + le16(35) + le64(9) + le64(0) + le64(60_000) +
            byteArrayOf(2) + byteArrayOf(1) + le32(120) + byteArrayOf(2) + le32(60)
        assertArrayEquals(expected, bytes)
        assertEquals(record, HealthTlv.decodeBatch(byteArrayOf(1) + bytes).single())
    }

    @Test
    fun workoutHeaderGoldenBytes() {
        val record = HealthTlv.WorkoutHeader(
            recordId = 0xFFFFL, activity = HealthTlv.ACTIVITY_RUNNING,
            startEpochMs = 100L, endEpochMs = 3_700_000L,
            activeMinutes = 60L, caloriesTenths = 450, distanceMeters = 10_000L,
            avgHrBpm = 150, maxHrBpm = 180, hrRecordCount = 3, gpsRecordCount = 2
        )
        val bytes = HealthTlv.encodeRecord(record)
        val expected = byteArrayOf(5) + le16(43) + le64(0xFFFF) + byteArrayOf(0) +
            le64(100) + le64(3_700_000) + le32(60) + le16(450) + le32(10_000) +
            le16(150) + le16(180) + le16(3) + le16(2)
        assertArrayEquals(expected, bytes)
        assertEquals(record, HealthTlv.decodeBatch(byteArrayOf(1) + bytes).single())
    }

    @Test
    fun workoutHrStreamGoldenBytes() {
        val record = HealthTlv.WorkoutHrStream(
            recordId = 5L,
            samples = listOf(
                HealthTlv.HrSample(1_000L, 120),
                HealthTlv.HrSample(2_000L, 125)
            )
        )
        val bytes = HealthTlv.encodeRecord(record)
        val expected = byteArrayOf(6) + le16(29) + le64(5) + byteArrayOf(2) +
            le64(1_000) + le16(120) + le64(2_000) + le16(125)
        assertArrayEquals(expected, bytes)
        assertEquals(record, HealthTlv.decodeBatch(byteArrayOf(1) + bytes).single())
    }

    @Test
    fun workoutGpsGoldenBytes() {
        val record = HealthTlv.WorkoutGps(
            recordId = 6L,
            points = listOf(
                HealthTlv.GpsPoint(latitudeE7 = 52_229_676, longitudeE7 = 2_104_114, altitudeMeters = 10.5f, timestampMs = 500L)
            )
        )
        val bytes = HealthTlv.encodeRecord(record)
        val altBits = java.lang.Float.floatToIntBits(10.5f)
        val expected = byteArrayOf(7) + le16(29) + le64(6) + byteArrayOf(1) +
            le32(52_229_676.toLong()) + le32(2_104_114.toLong()) + le32(altBits.toLong() and 0xFFFFFFFFL) + le64(500)
        assertArrayEquals(expected, bytes)
        assertEquals(record, HealthTlv.decodeBatch(byteArrayOf(1) + bytes).single())
    }

    @Test
    fun batteryGoldenBytes() {
        val bytes = HealthTlv.encodeRecord(HealthTlv.Battery(recordId = 1L, percent = 88, timestampMs = 77L))
        val expected = byteArrayOf(8) + le16(17) + le64(1) + byteArrayOf(88) + le64(77)
        assertArrayEquals(expected, bytes)
    }

    // ---- Batches ---------------------------------------------------------------

    @Test
    fun batchRoundTripMultipleRecords() {
        val records = listOf(
            HealthTlv.HeartRate(1, 60, 1000),
            HealthTlv.HeartRate(2, 61, 2000),
            HealthTlv.Battery(3, 50, 3000)
        )
        val batches = HealthTlv.buildBatches(records, maxBatchBytes = 509)
        assertEquals(1, batches.size)
        assertEquals(records, HealthTlv.decodeBatch(batches[0]))
    }

    @Test
    fun batchSplittingRespectsSizeCap() {
        val records = (1..20).map { HealthTlv.HeartRate(it.toLong(), 60, it * 1000L) }
        // Each record is 3 + 18 = 21 bytes; 1 + 21 = 22 fits, 1 + 2*21 = 43 exceeds 40.
        val batches = HealthTlv.buildBatches(records, maxBatchBytes = 40)
        assertEquals(20, batches.size) // one record per batch
        assertEquals(records, batches.flatMap { HealthTlv.decodeBatch(it) })
        batches.forEach { assert(it.size <= 40) }
    }

    @Test
    fun batchRespectsRecordCap() {
        val records = (1..60).map { HealthTlv.Battery(it.toLong(), 50, 0) }
        // 20 B/record (3 + 17); cap 1100 ≥ 1 + 50*20 isolates the 50-record cap.
        val batches = HealthTlv.buildBatches(records, maxBatchBytes = 1100)
        assertEquals(2, batches.size)
        assertEquals(50, HealthTlv.decodeBatch(batches[0]).size)
        assertEquals(10, HealthTlv.decodeBatch(batches[1]).size)
    }

    // ---- Control messages --------------------------------------------------------

    @Test
    fun helloGoldenBytes() {
        val bytes = HealthTlv.hello(installNonce = 0xAABBCCDDL, cursorRecordId = 77L)
        val expected = byteArrayOf(0x01) + le32(0xAABBCCDDL) + byteArrayOf(0xFF.toByte()) + le64(77)
        assertArrayEquals(expected, bytes)
        assertEquals(
            HealthTlv.ControlMessage.Hello(0xAABBCCDDL, 0xFF, 77L),
            HealthTlv.decodeControl(bytes)
        )
    }

    @Test
    fun ackGoldenBytes() {
        val ids = listOf(1L, 2L, 3L)
        val bytes = byteArrayOf(0x02, 3) + ids.flatMap { le64(it).toList() }.toByteArray()
        assertEquals(HealthTlv.ControlMessage.Ack(ids), HealthTlv.decodeControl(bytes))
    }

    @Test
    fun timeSyncPingPong() {
        assertEquals(
            HealthTlv.ControlMessage.TimeSync(1_700_000_000_000L),
            HealthTlv.decodeControl(byteArrayOf(0x03) + le64(1_700_000_000_000L))
        )
        assertArrayEquals(byteArrayOf(0x04), HealthTlv.ping())
        assertEquals(HealthTlv.ControlMessage.Ping, HealthTlv.decodeControl(byteArrayOf(0x04)))
        assertEquals(HealthTlv.ControlMessage.Pong, HealthTlv.decodeControl(byteArrayOf(0x05)))
    }

    // ---- Malformed input ------------------------------------------------------

    @Test
    fun rejectsTruncatedBatch() {
        val bytes = HealthTlv.encodeRecord(HealthTlv.HeartRate(1, 60, 1000))
        val batch = byteArrayOf(1) + bytes.copyOf(bytes.size - 3)
        assertThrows(HealthTlv.CodecException::class.java) { HealthTlv.decodeBatch(batch) }
    }

    @Test
    fun rejectsTrailingBytes() {
        val bytes = HealthTlv.encodeRecord(HealthTlv.Battery(1, 50, 0))
        val batch = byteArrayOf(1) + bytes + 0
        assertThrows(HealthTlv.CodecException::class.java) { HealthTlv.decodeBatch(batch) }
    }

    @Test
    fun rejectsUnknownRecordType() {
        val payload = le64(1) + le16(60)
        val batch = byteArrayOf(1, 99) + le16(payload.size) + payload
        assertThrows(HealthTlv.CodecException::class.java) { HealthTlv.decodeBatch(batch) }
    }

    @Test
    fun rejectsUnknownControlCommand() {
        assertThrows(HealthTlv.CodecException::class.java) { HealthTlv.decodeControl(byteArrayOf(0x7F)) }
    }
}
