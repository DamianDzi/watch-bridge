package com.wearos.ancsbridge.health

/**
 * In-memory step aggregation (roadmap B7). The OPW3's passive listener emits
 * a steps IntervalDataPoint every ~5 s with near-zero deltas even at rest —
 * inserting per point produced ~700 records/hour of noise. This bucket sums
 * deltas and yields ONE record per span, only when the sum is nonzero:
 * a stationary hour produces zero step records.
 *
 * Zero-delta points neither start nor extend a bucket, so a bucket's
 * interval always spans real movement (first nonzero start to last
 * nonzero end).
 *
 * Not thread-safe — callers must confine access (the collector
 * synchronizes on the instance).
 */
internal class StepsBucket(private val spanMs: Long) {

    data class Entry(val totalSteps: Long, val startMs: Long, val endMs: Long)

    private var total = 0L
    private var empty = true
    private var firstStartMs = 0L
    private var lastEndMs = 0L

    fun add(deltaSteps: Long, startMs: Long, endMs: Long) {
        if (deltaSteps <= 0) return
        if (empty) {
            empty = false
            firstStartMs = startMs
        }
        if (endMs > lastEndMs) lastEndMs = endMs
        total += deltaSteps
    }

    /** True when a nonzero accumulation has aged past the bucket span. */
    fun isDue(nowMs: Long): Boolean =
        !empty && total > 0 && nowMs - firstStartMs >= spanMs

    /**
     * Returns the summed entry when nonzero and resets all state; returns
     * null (and still resets) otherwise.
     */
    fun drain(): Entry? {
        val entry = if (!empty && total > 0) Entry(total, firstStartMs, lastEndMs) else null
        total = 0
        empty = true
        firstStartMs = 0
        lastEndMs = 0
        return entry
    }
}
