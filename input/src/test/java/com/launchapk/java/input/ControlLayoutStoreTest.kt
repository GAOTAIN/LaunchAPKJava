package com.launchapk.java.input

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControlLayoutStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun firstLoadCreatesPresetAndASecondStoreRestoresEdits() {
        val directory = temporary.newFolder()
        val store = ControlLayoutStore(directory)
        val initial = store.loadOrCreate()
        assertTrue(store.file.isFile)
        assertTrue(initial.buttons.any { it.keyCode == -1 })
        assertTrue(initial.joysticks.isNotEmpty())
        val updated = initial.copy(buttons = initial.buttons.drop(1).map {
            if (it.id == "space") it.copy(label = "跳跃", keyCode = 32, x = .55f, width = .2f, alpha = .5f) else it
        }, joysticks = initial.joysticks.map { it.copy(x = .2f, radius = .12f) })
        store.save(updated)
        assertEquals(updated, ControlLayoutStore(directory).loadOrCreate())
        assertFalse(store.file.parentFile!!.resolve(".default.json.tmp").exists())
    }
    @Test fun invalidSaveDoesNotDestroyTheExistingLayout() {
        val store = ControlLayoutStore(temporary.newFolder())
        val valid = store.loadOrCreate()
        val original = store.file.readText()
        val invalid = valid.copy(buttons = valid.buttons.map { it.copy(alpha = Float.NaN) })
        assertThrows(IllegalArgumentException::class.java) { store.save(invalid) }
        assertEquals(original, store.file.readText())
    }
    @Test fun corruptLoadIsReportedAndNeverOverwritesTheFile() {
        val store = ControlLayoutStore(temporary.newFolder())
        store.loadOrCreate()
        store.file.writeText("{broken")
        assertThrows(Exception::class.java) { store.loadOrCreate() }
        assertEquals("{broken", store.file.readText())
    }
    @Test fun versionAndDuplicateIdsAreRejected() {
        val preset = presetControlLayout()
        assertThrows(IllegalArgumentException::class.java) { ControlLayoutJson.decode(ControlLayoutJson.encode(preset).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")) }
        assertThrows(IllegalArgumentException::class.java) { preset.copy(buttons = preset.buttons + preset.buttons.first()).validated() }
    }
    @Test fun draggingClampsToTheViewport() {
        val moved = presetControlLayout().moveControl("space", 10f, -10f, ControlViewport(800f, 400f))
        val button = moved.buttons.first { it.id == "space" }
        assertEquals(1f - button.width, button.x, .0001f)
        assertEquals(0f, button.y, .0001f)
        val stick = moved.moveControl("move", -10f, 10f, ControlViewport(800f, 400f)).joysticks.first()
        assertEquals(stick.radius / 2, stick.x, .0001f)
        assertEquals(1f - stick.radius, stick.y, .0001f)
    }
}
