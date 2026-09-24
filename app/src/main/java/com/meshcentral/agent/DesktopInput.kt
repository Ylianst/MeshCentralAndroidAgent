package com.meshcentral.agent

// MeshCentral mouse message flags (byte 5). A button's up flag is its down flag doubled and
// 0x88 is the viewer's double-click event, which follows the two down/up pairs it already sent.
internal enum class MouseButton { LEFT, RIGHT, MIDDLE }

internal sealed class MouseInput {
    data class Down(val button: MouseButton) : MouseInput()
    data class Up(val button: MouseButton) : MouseInput()
    object Move : MouseInput()
    object DoubleClick : MouseInput()
    object Unknown : MouseInput()
}

internal fun decodeMouseFlags(flags: Int): MouseInput = when (flags) {
    0x00 -> MouseInput.Move
    0x02 -> MouseInput.Down(MouseButton.LEFT)
    0x04 -> MouseInput.Up(MouseButton.LEFT)
    0x08 -> MouseInput.Down(MouseButton.RIGHT)
    0x10 -> MouseInput.Up(MouseButton.RIGHT)
    0x20 -> MouseInput.Down(MouseButton.MIDDLE)
    0x40 -> MouseInput.Up(MouseButton.MIDDLE)
    0x88 -> MouseInput.DoubleClick
    else -> MouseInput.Unknown
}

// Touch message pointer flags (Windows POINTER_FLAG_* values as sent by the viewer).
internal const val TOUCH_FLAG_DOWN = 0x00010000
internal const val TOUCH_FLAG_UPDATE = 0x00020000
internal const val TOUCH_FLAG_UP = 0x00040000
