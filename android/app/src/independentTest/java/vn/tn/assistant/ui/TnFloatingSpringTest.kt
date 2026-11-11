package vn.tn.assistant.ui

import org.junit.Assert.*
import org.junit.Test

class TnFloatingSpringTest {
    @Test fun dragSpringSettlesAndThenNeedsNoFrames() {
        val spring = TnFloatingSpring(50.0, 200.0, 20.0)
        spring.target = 400.0
        repeat(500) { spring.advance(1.0 / 60) }
        assertEquals(400.0, spring.value, .005)
        assertFalse(spring.needsStep)
    }
    @Test fun resumedFrameTimeIsBoundedToAvoidExplodingMotion() {
        val longFrame = TnFloatingSpring(0.0, 200.0, 20.0).apply { target = 200.0 }
        val boundedFrame = TnFloatingSpring(0.0, 200.0, 20.0).apply { target = 200.0 }
        longFrame.advance(60.0); boundedFrame.advance(.064)
        assertEquals(boundedFrame.value, longFrame.value, .000001)
        assertTrue(longFrame.value.isFinite())
    }
    @Test fun interruptedDragCanSnapToTheCurrentWindowCoordinates() {
        val spring = TnFloatingSpring(10.0, 200.0, 20.0).apply { target = 500.0 }
        repeat(4) { spring.advance(1.0 / 60) }
        spring.snap(200.0)
        assertEquals(200.0, spring.value, 0.0)
        assertEquals(200.0, spring.target, 0.0)
        assertTrue(spring.isAtRest)
    }
}
