package vn.tn.assistant.ui

import org.junit.Assert.*
import org.junit.Test

class TnFloatingGeometryTest {
    @Test fun sizeTracksReferenceOrientationAndUserSelection() {
        // Live original 1080px portrait origin=270, base slot=135.
        assertEquals(135, TnFloatingGeometry.unit(1080, false, "standard"))
        assertEquals(114, TnFloatingGeometry.unit(1080, false, "small"))
        assertEquals(168, TnFloatingGeometry.unit(1080, false, "large"))
        assertEquals(120, TnFloatingGeometry.unit(1600, true, "standard"))
        assertEquals(152 to 158, TnFloatingGeometry.dimensions(135, false, 2.625f))
        assertEquals(76, TnFloatingGeometry.hintAnchorOffset(135, false, 2.625f))
    }
    @Test fun tinyViewportCannotCreateOffscreenOrigin() {
        assertEquals(0, TnFloatingGeometry.clampOrigin(30, 40, 80))
        assertEquals(0, TnFloatingGeometry.clampOrigin(-3, 1600, 255))
        assertEquals(1345, TnFloatingGeometry.clampOrigin(2000, 1600, 255))
        assertTrue(TnFloatingGeometry.maximumOriginY(80, 120f, 12f) >= 0)
    }
    @Test fun closeMagnetHasSeparateEntryAndExitRadii() {
        // A cluster ending 90 px left of the target is outside 75 px entry,
        // but remains held within 125 px exit after it has been magnetized.
        assertFalse(TnFloatingGeometry.nearCloseTarget(500, 500, 210f, 450f, 200, 100, 100, false))
        assertTrue(TnFloatingGeometry.nearCloseTarget(500, 500, 210f, 450f, 200, 100, 100, true))
        assertTrue(TnFloatingGeometry.nearCloseTarget(500, 500, 300f, 450f, 200, 100, 100, false))
    }
    @Test fun opacityDoesNotHideTheUtilityUnexpectedly() {
        assertEquals(1f, TnFloatingGeometry.opacity("auto"), 0f)
        assertEquals(1f, TnFloatingGeometry.opacity("normal"), 0f)
        assertEquals(.8f, TnFloatingGeometry.opacity("faint"), 0f)
        assertEquals(.6f, TnFloatingGeometry.opacity("very_faint"), 0f)
        assertEquals(1f, TnFloatingGeometry.opacity("unknown"), 0f)
    }
}
