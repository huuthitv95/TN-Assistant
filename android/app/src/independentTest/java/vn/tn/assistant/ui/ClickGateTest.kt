package vn.tn.assistant.ui

import org.junit.Assert.*
import org.junit.Test

class ClickGateTest {
    @Test fun suppressesRapidClicksAcrossWelcomePagesWithoutExtendingWindow() {
        val gate = ClickGate()
        assertTrue(gate.accept(10_000))
        assertFalse(gate.accept(10_100))
        assertFalse(gate.accept(10_500))
        assertTrue(gate.accept(10_501))
    }

    @Test fun acceptsFirstClickAndRecoversIfTimeSourceMovesBackwards() {
        val gate = ClickGate()
        assertTrue(gate.accept(0))
        assertFalse(gate.accept(0))
        assertTrue(gate.accept(501))
        assertTrue(gate.accept(100))
        assertFalse(gate.accept(599))
    }
}
