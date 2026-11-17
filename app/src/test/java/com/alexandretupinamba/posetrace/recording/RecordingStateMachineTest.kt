package com.alexandretupinamba.posetrace.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingStateMachineTest {
    @Test
    fun happyPathRequiresExplicitStates() {
        val machine = RecordingStateMachine()

        machine.transitionTo(RecordingState.PREPARING)
        machine.transitionTo(RecordingState.RECORDING)
        machine.transitionTo(RecordingState.STOPPING)
        machine.transitionTo(RecordingState.COMPLETED)

        assertEquals(RecordingState.COMPLETED, machine.state)

        machine.transitionTo(RecordingState.IDLE)
        assertEquals(RecordingState.IDLE, machine.state)
    }

    @Test
    fun invalidTransitionIsRejected() {
        val machine = RecordingStateMachine()

        assertThrows(IllegalArgumentException::class.java) {
            machine.transitionTo(RecordingState.COMPLETED)
        }
    }
}
