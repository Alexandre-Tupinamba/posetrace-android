package com.alexandretupinamba.posetrace.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicTimestampMapperTest {
    @Test
    fun mapsNanosecondsToMilliseconds() {
        val mapper = MonotonicTimestampMapper()

        assertEquals(1_234L, mapper.map(1_234_999_999L))
    }

    @Test
    fun forcesUniqueIncreasingMillisecondsWhenFramesShareSameMillisecond() {
        val mapper = MonotonicTimestampMapper()

        val first = mapper.map(10_000_100L)
        val second = mapper.map(10_000_900L)

        assertEquals(10L, first)
        assertEquals(11L, second)
        assertTrue(second > first)
    }

    @Test
    fun neverMovesBackwardsWhenCameraTimestampCandidateIsEarlier() {
        val mapper = MonotonicTimestampMapper()

        val first = mapper.map(50_000_000L)
        val second = mapper.map(40_000_000L)

        assertEquals(50L, first)
        assertEquals(51L, second)
    }
}
