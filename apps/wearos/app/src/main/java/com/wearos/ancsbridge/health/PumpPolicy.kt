package com.wearos.ancsbridge.health

/**
 * Pure flush decision for the health sync pump (B7: unit-tested — the
 * debounce arithmetic is the only real logic in the pump trigger).
 */
internal object PumpPolicy {
    fun shouldPump(
        pendingSignals: Int,
        firstPendingAt: Long,
        now: Long,
        recordThreshold: Int,
        maxDelayMs: Long
    ): Boolean =
        pendingSignals >= recordThreshold ||
            (firstPendingAt > 0 && now - firstPendingAt >= maxDelayMs)
}
