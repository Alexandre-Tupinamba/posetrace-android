package com.alexandretupinamba.posetrace.pose

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayPoseStabilizerTest {
    @Test
    fun rawPoseFieldsRemainUntouched() {
        val stabilizer = DisplayPoseStabilizer()
        val original = point(
            rawX = 0.2f,
            rawY = 0.3f,
            displayX = 0.45f,
            displayY = 0.55f,
        )

        stabilizer.stabilize(listOf(original), timestampNs = 0L)
        val stabilized = stabilizer.stabilize(
            listOf(original.copy(displayX = 0.47f, displayY = 0.53f)),
            timestampNs = FRAME_NS,
        ).single()

        assertEquals(original.x, stabilized.x)
        assertEquals(original.y, stabilized.y)
        assertEquals(original.z, stabilized.z)
        assertEquals(original.worldX, stabilized.worldX)
        assertEquals(original.worldY, stabilized.worldY)
        assertEquals(original.worldZ, stabilized.worldZ)
    }

    @Test
    fun stationaryJitterIsReduced() {
        val stabilizer = DisplayPoseStabilizer()
        val rawValues = listOf(
            0.50f,
            0.49f,
            0.51f,
            0.49f,
            0.51f,
            0.50f,
            0.49f,
            0.51f,
        )

        val filteredValues = rawValues.mapIndexed { index, value ->
            stabilizer.stabilize(
                listOf(point(displayX = value, displayY = 0.5f)),
                timestampNs = index * FRAME_NS,
            ).single().displayX!!
        }

        assertTrue(totalVariation(filteredValues) < totalVariation(rawValues))
    }

    @Test
    fun fastMotionKeepsResponsiveCutoff() {
        val stabilizer = DisplayPoseStabilizer()

        stabilizer.stabilize(
            listOf(point(displayX = 0.0f, displayY = 0.5f)),
            timestampNs = 0L,
        )
        stabilizer.stabilize(
            listOf(point(displayX = 0.05f, displayY = 0.5f)),
            timestampNs = FRAME_NS,
        )
        val fast = stabilizer.stabilize(
            listOf(point(displayX = 0.60f, displayY = 0.5f)),
            timestampNs = 2 * FRAME_NS,
        ).single()

        assertTrue(
            "Adaptive cutoff should follow fast motion instead of heavily lagging",
            fast.displayX!! > 0.20f,
        )
    }

    @Test
    fun poseLossResetsFilterState() {
        val stabilizer = DisplayPoseStabilizer()

        stabilizer.stabilize(
            listOf(point(displayX = 0.2f, displayY = 0.3f)),
            timestampNs = 0L,
        )
        stabilizer.stabilize(
            listOf(point(displayX = 0.8f, displayY = 0.7f)),
            timestampNs = FRAME_NS,
        )

        stabilizer.stabilize(emptyList(), timestampNs = 2 * FRAME_NS)

        val reacquired = stabilizer.stabilize(
            listOf(point(displayX = 0.1f, displayY = 0.9f)),
            timestampNs = 3 * FRAME_NS,
        ).single()

        assertEquals(0.1f, reacquired.displayX)
        assertEquals(0.9f, reacquired.displayY)
    }

    @Test
    fun disablingReturnsUnfilteredDisplayCoordinates() {
        val stabilizer = DisplayPoseStabilizer()
        stabilizer.setEnabled(false)
        val input = point(displayX = 0.33f, displayY = 0.77f)

        val output = stabilizer.stabilize(listOf(input), timestampNs = FRAME_NS).single()

        assertEquals(input, output)
    }

    private fun totalVariation(values: List<Float>): Float =
        values.zipWithNext().sumOf { (a, b) -> abs(b - a).toDouble() }.toFloat()

    private fun point(
        rawX: Float = 0.12f,
        rawY: Float = 0.34f,
        displayX: Float,
        displayY: Float,
    ): PosePoint =
        PosePoint(
            index = 0,
            x = rawX,
            y = rawY,
            z = -0.1f,
            visibility = 0.9f,
            presence = 0.9f,
            worldX = 0.4f,
            worldY = 0.5f,
            worldZ = 0.6f,
            worldVisibility = 0.8f,
            worldPresence = 0.8f,
            displayX = displayX,
            displayY = displayY,
        )

    companion object {
        private const val FRAME_NS = 33_333_333L
    }
}
