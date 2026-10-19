package com.launchapk.java.renderer

/** Future backends must supply their own libraries and compatibility validation. */
enum class RendererBackend(val title: String, val available: Boolean) {
    GL4ES("GL4ES", true), ZINK("Zink", false), VIRGL("VirGL", false),
}
