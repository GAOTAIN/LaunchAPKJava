package com.launchapk.java.input

import org.junit.Assert.*
import org.junit.Test

class InputSessionTest {
    private class RecordingBridge : InputBridge {
        val events = mutableListOf<String>()
        override fun sendKey(keyCode: Int, pressed: Boolean) { events += "key:$keyCode:$pressed" }
        override fun sendMouseButton(button: Int, pressed: Boolean) { events += "mouse:$button:$pressed" }
        override fun sendPointer(id: Int, x: Float, y: Float, action: Int) { events += "pointer:$id:$x:$y:$action" }
    }
    @Test fun multipleOwnersKeepTheKeyHeldUntilLastRelease() {
        val bridge = RecordingBridge()
        val session = InputSession(bridge)
        val w = presetControlLayout().buttons.first { it.id == "w" }
        session.pressButton(1, w)
        session.pressButton(2, w)
        session.release(1)
        assertEquals(listOf("key:87:true"), bridge.events)
        session.release(2)
        assertEquals(listOf("key:87:true", "key:87:false"), bridge.events)
    }
    @Test fun joystickSharesKeysWithButtonsAndReleasesDiagonalOnCancellation() {
        val bridge = RecordingBridge()
        val session = InputSession(bridge)
        val preset = presetControlLayout()
        session.pressButton(1, preset.buttons.first { it.id == "w" })
        session.pressJoystick(2, preset.joysticks.first(), .8f, -.8f)
        session.release(1)
        assertEquals(setOf("key:87:true", "key:68:true"), bridge.events.toSet())
        session.releaseAll()
        assertTrue(bridge.events.containsAll(listOf("key:87:false", "key:68:false")))
        assertTrue(session.state.value.activeIds.isEmpty())
        val count = bridge.events.size
        session.releaseAll()
        assertEquals(count, bridge.events.size)
    }
    @Test fun mouseButtonsAreNotSentAsKeyboardKeys() {
        val bridge = RecordingBridge()
        val session = InputSession(bridge)
        val preset = presetControlLayout()
        session.pressButton(3, preset.buttons.first { it.id == "left" })
        session.pressButton(4, preset.buttons.first { it.id == "right" })
        session.releaseAll()
        assertEquals(setOf("mouse:0:true", "mouse:1:true", "mouse:0:false", "mouse:1:false"), bridge.events.toSet())
    }
    @Test fun lookGetsDownMoveAndCancelAtLatestPosition() {
        val bridge = RecordingBridge()
        val session = InputSession(bridge)
        session.beginLook(7, 30f, 40f)
        session.moveLook(7, 50f, 60f)
        session.releaseAll()
        assertEquals(listOf("pointer:7:30.0:40.0:0", "pointer:7:50.0:60.0:2", "pointer:7:50.0:60.0:3"), bridge.events)
    }
    @Test fun cancelledJoystickCannotBeResurrectedByAnOldMoveEvent() {
        val bridge = RecordingBridge()
        val session = InputSession(bridge)
        val stick = presetControlLayout().joysticks.first()
        session.pressJoystick(1, stick, 1f, 0f)
        session.releaseAll()
        val count = bridge.events.size
        session.moveJoystick(1, stick, 0f, -1f)
        assertEquals(count, bridge.events.size)
        assertTrue(session.state.value.activeIds.isEmpty())
    }
    @Test fun joystickDeadZoneReleasesMovementWithoutLiftingFinger() {
        val bridge = RecordingBridge()
        val session = InputSession(bridge)
        val stick = presetControlLayout().joysticks.first()
        session.pressJoystick(1, stick, 0f, -1f)
        session.moveJoystick(1, stick, .05f, -.05f)
        assertEquals(listOf("key:87:true", "key:87:false"), bridge.events)
        session.release(1)
    }
}
