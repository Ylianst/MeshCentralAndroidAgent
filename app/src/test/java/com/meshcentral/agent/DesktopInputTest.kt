package com.meshcentral.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopInputTest {
    @Test
    fun decodesNormalAndExtendedKeys() {
        assertEquals(true, decodeKeyDown(0))
        assertEquals(true, decodeKeyDown(4))
        assertEquals(false, decodeKeyDown(1))
        assertEquals(false, decodeKeyDown(3))
        for (action in listOf(-1, 2, 5, 255)) assertEquals(null, decodeKeyDown(action))
    }

    @Test
    fun decodesButtonTransitions() {
        assertEquals(MouseInput.Move, decodeMouseFlags(0x00))
        assertEquals(MouseInput.Down(MouseButton.LEFT), decodeMouseFlags(0x02))
        assertEquals(MouseInput.Up(MouseButton.LEFT), decodeMouseFlags(0x04))
        assertEquals(MouseInput.Down(MouseButton.RIGHT), decodeMouseFlags(0x08))
        assertEquals(MouseInput.Up(MouseButton.RIGHT), decodeMouseFlags(0x10))
        assertEquals(MouseInput.Down(MouseButton.MIDDLE), decodeMouseFlags(0x20))
        assertEquals(MouseInput.Up(MouseButton.MIDDLE), decodeMouseFlags(0x40))
        assertEquals(MouseInput.DoubleClick, decodeMouseFlags(0x88))
        assertEquals(MouseInput.Unknown, decodeMouseFlags(0x03))
    }
}
