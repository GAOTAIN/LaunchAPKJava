package com.launchapk.java.input

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ControlLayoutJson {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    fun encode(layout: ControlLayout): String = gson.toJson(layout.validated())
    fun decode(text: String): ControlLayout {
        require(text.length <= 1024 * 1024) { "布局文件过大" }
        val root = JsonParser.parseString(text).asJsonObject
        require(root.get("schemaVersion")?.asInt == 1) { "不支持的布局版本" }
        val buttons = root.getAsJsonArray("buttons") ?: error("缺少 buttons")
        val sticks = root.getAsJsonArray("joysticks") ?: error("缺少 joysticks")
        return ControlLayout(
            buttons = buttons.map { element ->
                val b = element.asJsonObject
                ControlButton(b["id"].asString, b["label"].asString, b["keyCode"].asInt,
                    b["x"].asFloat, b["y"].asFloat, b["width"].asFloat, b["height"].asFloat, b["alpha"].asFloat)
            },
            joysticks = sticks.map { element ->
                val j = element.asJsonObject
                ControlJoystick(j["id"].asString, j["x"].asFloat, j["y"].asFloat, j["radius"].asFloat)
            },
        ).validated()
    }
}

// Pass context.filesDir. The filename is fixed, never obtained from imported JSON.
class ControlLayoutStore(filesDir: File) {
    private val directory = File(filesDir, "controls")
    val file = File(directory, "default.json")

    @Synchronized
    fun loadOrCreate(): ControlLayout {
        if (!file.exists()) return presetControlLayout().also(::save)
        require(file.length() <= 1024 * 1024) { "布局文件过大" }
        return ControlLayoutJson.decode(file.readText(Charsets.UTF_8))
    }

    @Synchronized
    fun save(layout: ControlLayout) {
        val json = ControlLayoutJson.encode(layout) // Validate before touching the existing file.
        check(directory.isDirectory || directory.mkdirs()) { "无法创建布局目录" }
        val temporary = File(directory, ".default.json.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
