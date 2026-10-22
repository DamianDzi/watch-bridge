package com.wearos.ancsbridge.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsBucketTest {

    private val spanMs = 300_000L

    @Test
    fun zeroDeltasProduceNothing() {
        val bucket = StepsBucket(spanMs)
        // One zero point every 5 s for a simulated stationary hour.
        for (i in 0 until 720) bucket.add(0, i * 5_000L, (i + 1) * 5_000L)
        assertFalse(bucket.isDue(3_600_000L))
        assertNull(bucket.drain())
    }

    @Test
    fun nonzeroDeltasAccumulateAndFlushAsOneRecord() {
        val bucket = StepsBucket(spanMs)
        for (i in 0 until 60) bucket.add(2, i * 5_000L, (i + 1) * 5_000L) // 5 min of walking
        assertTrue(bucket.isDue(300_000L))
        val entry = bucket.drain()
        assertNotNull(entry)
        assertEquals(120L, entry!!.totalSteps)
        assertEquals(0L, entry.startMs)
        assertEquals(300_000L, entry.endMs)
    }

    @Test
    fun notDueBeforeSpanElapses() {
        val bucket = StepsBucket(spanMs)
        bucket.add(10, 0L, 5_000L)
        assertFalse(bucket.isDue(299_999L))
        assertTrue(bucket.isDue(300_000L))
    }

    @Test
    fun drainResetsState() {
        val bucket = StepsBucket(spanMs)
        bucket.add(5, 0L, 5_000L)
        assertNotNull(bucket.drain())
        assertNull(bucket.drain()) // second drain: nothing
        // fresh bucket after drain: due-ness restarts with new movement
        bucket.add(3, 400_000L, 405_000L)
        assertFalse(bucket.isDue(500_000L))
        assertTrue(bucket.isDue(700_000L))
    }

    @Test
    fun intervalTracksFirstNonzeroStartAndLatestEnd() {
        val bucket = StepsBucket(spanMs)
        // stationary (zero) points before the walk must not extend the interval
        for (i in 0 until 12) bucket.add(0, i * 5_000L, (i + 1) * 5_000L)
        bucket.add(4, 60_000L, 65_000L)
        bucket.add(6, 65_000L, 70_000L)
        // out-of-order end must not move lastEndMs backwards
        bucket.add(1, 40_000L, 45_000L)
        val entry = bucket.drain()
        assertNotNull(entry)
        assertEquals(11L, entry!!.totalSteps)
        assertEquals(60_000L, entry.startMs)
        assertEquals(70_000L, entry.endMs)
    }
}
