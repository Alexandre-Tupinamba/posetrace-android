package com.alexandretupinamba.posetrace.pose

import kotlin.math.PI
import kotlin.math.abs

internal class DisplayPoseStabilizer(
    private val minCutoff: Double = 1.7,
    private val beta: Double = 0.7,
    private val derivativeCutoff: Double = 1.0,
    private val resetGapNs: Long = 250_000_000L,
) {
    private data class LandmarkFilters(
        val x: OneEuroFilter,
        val y: OneEuroFilter,
    )

    private val filters = mutableMapOf<Int, LandmarkFilters>()
    private var lastTimestampNs: Long? = null
    private var enabled = true

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        resetLocked()
    }

    @Synchronized
    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun stabilize(
        points: List<PosePoint>,
        timestampNs: Long,
    ): List<PosePoint> {
        if (!enabled) return points

        if (points.isEmpty()) {
            resetLocked()
            return points
        }

        val previousTimestamp = lastTimestampNs
        if (
            previousTimestamp != null &&
            (timestampNs <= previousTimestamp || timestampNs - previousTimestamp > resetGapNs)
        ) {
            resetLocked()
        }
        lastTimestampNs = timestampNs

        return points.map { point ->
            val displayX = point.displayX
            val displayY = point.displayY
            if (displayX == null || displayY == null) {
                point
            } else {
                val landmarkFilters = filters.getOrPut(point.index) {
                    LandmarkFilters(
                        x = OneEuroFilter(minCutoff, beta, derivativeCutoff),
                        y = OneEuroFilter(minCutoff, beta, derivativeCutoff),
                    )
                }

                point.copy(
                    displayX = landmarkFilters.x.filter(displayX, timestampNs),
                    displayY = landmarkFilters.y.filter(displayY, timestampNs),
                )
            }
        }
    }

    @Synchronized
    fun reset() {
        resetLocked()
    }

    private fun resetLocked() {
        filters.clear()
        lastTimestampNs = null
    }
}

private class OneEuroFilter(
    private val minCutoff: Double,
    private val beta: Double,
    private val derivativeCutoff: Double,
) {
    private var previousTimestampNs: Long? = null
    private var previousRawValue: Double? = null
    private var previousFilteredValue: Double? = null
    private var previousFilteredDerivative = 0.0

    fun filter(
        value: Float,
        timestampNs: Long,
    ): Float {
        val raw = value.toDouble()
        val previousTimestamp = previousTimestampNs
        val previousRaw = previousRawValue
        val previousFiltered = previousFilteredValue

        if (
            previousTimestamp == null ||
            previousRaw == null ||
            previousFiltered == null ||
            timestampNs <= previousTimestamp
        ) {
            previousTimestampNs = timestampNs
            previousRawValue = raw
            previousFilteredValue = raw
            previousFilteredDerivative = 0.0
            return value
        }

        val dtSeconds = (timestampNs - previousTimestamp) / NS_PER_SECOND
        if (dtSeconds <= 0.0) return value

        val derivative = (raw - previousRaw) / dtSeconds
        val derivativeAlpha = alpha(derivativeCutoff, dtSeconds)
        val filteredDerivative = lowPass(
            previous = previousFilteredDerivative,
            current = derivative,
            alpha = derivativeAlpha,
        )

        val cutoff = minCutoff + beta * abs(filteredDerivative)
        val valueAlpha = alpha(cutoff, dtSeconds)
        val filtered = lowPass(
            previous = previousFiltered,
            current = raw,
            alpha = valueAlpha,
        )

        previousTimestampNs = timestampNs
        previousRawValue = raw
        previousFilteredValue = filtered
        previousFilteredDerivative = filteredDerivative

        return filtered.toFloat()
    }

    private fun alpha(
        cutoff: Double,
        dtSeconds: Double,
    ): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dtSeconds)
    }

    private fun lowPass(
        previous: Double,
        current: Double,
        alpha: Double,
    ): Double = alpha * current + (1.0 - alpha) * previous

    companion object {
        private const val NS_PER_SECOND = 1_000_000_000.0
    }
}
