package com.wearos.ancsbridge.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpPolicyTest {

    private val threshold = 20
    private val maxDelayMs = 120_000L

    @Test
    fun burstsFlushAtRecordThreshold() {
        assertTrue(PumpPolicy.shouldPump(threshold, 1_000L, 2_000L, threshold, maxDelayMs))
        assertTrue(PumpPolicy.shouldPump(threshold + 5, 1_000L, 1_500L, threshold, maxDelayMs))
    }

    @Test
    fun loneRecordFlushesAfterMaxDelay() {
        assertTrue(PumpPolicy.shouldPump(1, 0L + maxDelayMs, maxDelayMs * 2, threshold, maxDelayMs))
        // one millisecond early — not yet
        assertFalse(PumpPolicy.shouldPump(1, 100L, 100L + maxDelayMs - 1, threshold, maxDelayMs))
    }

    @Test
    fun freshRecordsDoNotPump() {
        assertFalse(PumpPolicy.shouldPump(1, 1_000L, 2_000L, threshold, maxDelayMs))
        assertFalse(PumpPolicy.shouldPump(threshold - 1, 1_000L, 2_000L, threshold, maxDelayMs))
    }

    @Test
    fun noPendingSignalNeverPumps() {
        // firstPendingAt == 0 means nothing is waiting — the delay arm must not fire
        assertFalse(PumpPolicy.shouldPump(0, 0L, 10_000_000L, threshold, maxDelayMs))
    }
}
