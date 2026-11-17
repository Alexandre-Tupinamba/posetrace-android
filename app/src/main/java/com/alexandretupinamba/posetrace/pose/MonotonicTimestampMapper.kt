package com.alexandretupinamba.posetrace.pose

/**
 * Converts a nanosecond camera timestamp into a MediaPipe millisecond timestamp
 * while guaranteeing strict monotonicity after integer division.
 */
internal class MonotonicTimestampMapper {
    private var lastTimestampMs = Long.MIN_VALUE

    @Synchronized
    fun map(cameraTimestampNs: Long): Long {
        val candidate = cameraTimestampNs / NS_PER_MS
        val mapped = if (lastTimestampMs == Long.MIN_VALUE) {
            candidate
        } else {
            maxOf(candidate, lastTimestampMs + 1L)
        }
        lastTimestampMs = mapped
        return mapped
    }

    companion object {
        private const val NS_PER_MS = 1_000_000L
    }
}
