package com.launchapk.java.input

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.launchapk.java.ui.GlassBackground
import com.launchapk.java.ui.GlassButton
import com.launchapk.java.ui.GlassCard
import com.launchapk.java.ui.LocalGlassTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun ControlDebugScreen(session: InputSession, lastEvent: String, onExit: () -> Unit,
    gameContent: (@Composable () -> Unit)? = null, onEditingChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ControlLayoutStore(context.filesDir) }
    var layout by remember { mutableStateOf<ControlLayout?>(null) }
    var savedLayout by remember { mutableStateOf<ControlLayout?>(null) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var inspector by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在读取布局…") }
    val scope = rememberCoroutineScope()
    val tokens = LocalGlassTokens.current
    val dirty = layout != null && layout != savedLayout

    LaunchedEffect(store) {
        try {
            val loaded = withContext(Dispatchers.IO) { store.loadOrCreate() }
            layout = loaded
            savedLayout = loaded
            status = "已加载 default.json；空白区域可滑动视角"
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            layout = presetControlLayout()
            status = "读取失败：${e.message}。已显示预设；点击保存将替换原文件。"
        }
    }
    LaunchedEffect(editing, inspector, confirmReset, confirmExit) {
        session.releaseAll()
        onEditingChanged(editing || inspector || confirmReset || confirmExit)
    }
    fun requestExit() {
        session.releaseAll()
        if (dirty) confirmExit = true else onExit()
    }
    fun save(exitAfter: Boolean = false) {
        val snapshot = layout ?: return
        if (saving) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(snapshot) }
                savedLayout = snapshot
                status = "已保存；重启应用后将恢复此布局"
                if (exitAfter) onExit()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status = "保存失败：${e.message}"
            } finally { saving = false }
        }
    }
    BackHandler { if (!saving) requestExit() }
    GlassBackground {
        if (gameContent == null) SimulatedScene()
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (gameContent != null) gameContent()
            val current = layout
            if (current != null) ControlOverlay(current, editing, session, selected,
                onSelect = { selected = it }, onLayoutChange = { layout = it })
            Column(Modifier.align(Alignment.TopCenter).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("返回", { if (!saving) requestExit() }, emphasized = false, enabled = !saving)
                    GlassButton(if (editing) "切换游玩" else "编辑模式", {
                        session.releaseAll(); editing = !editing; selected = null
                    }, enabled = current != null && !saving)
                    if (editing && current != null) {
                        GlassButton("加按键", {
                            val id = "button_" + UUID.randomUUID().toString().replace("-", "")
                            layout = current.copy(buttons = current.buttons + ControlButton(id, "新按键", 32, .45f, .40f, .12f, .16f))
                            selected = id; inspector = true
                        }, emphasized = false, enabled = current.buttons.size < 64)
                        GlassButton("加摇杆", {
                            val id = "stick_" + UUID.randomUUID().toString().replace("-", "")
                            layout = current.copy(joysticks = current.joysticks + ControlJoystick(id, .50f, .55f, .14f))
                            selected = id; inspector = true
                        }, emphasized = false, enabled = current.joysticks.size < 8)
                        GlassButton("属性", { inspector = true }, emphasized = false, enabled = selected != null)
                        GlassButton("恢复预设", { confirmReset = true }, emphasized = false)
                    }
                }
                Text(if (editing) "编辑：点选并拖动控件；在“属性”中缩放、改键或调整透明度" else if (gameContent != null) "游玩：空白处点击菜单／滑动视角；按键和摇杆已连接 GLFW" else "游玩：按住按键／摇杆；空白区域滑动视角（仅调试事件）",
                    color = tokens.primaryText)
            }
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp).widthIn(max = 420.dp)) {
                Text(status + if (dirty) " · 未保存" else "", color = tokens.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(lastEvent, color = tokens.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (editing) GlassButton(if (saving) "保存中…" else "保存" + if (dirty) " *" else "", { save() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), enabled = current != null && !saving)
        }
    }
    if (inspector && selected != null && layout != null) {
        ControlInspector(layout!!, selected!!, onChange = { layout = it }, onDismiss = { inspector = false })
    }
    if (confirmReset) {
        Dialog(onDismissRequest = { confirmReset = false }) {
            GlassCard {
                Text("恢复预设布局？", color = tokens.primaryText)
                Text("替换当前编辑内容；点击保存后才会写入文件。", color = tokens.secondaryText)
                GlassButton("恢复预设", { layout = presetControlLayout(); selected = null; confirmReset = false })
                GlassButton("取消", { confirmReset = false }, emphasized = false)
            }
        }
    }
    if (confirmExit) {
        Dialog(onDismissRequest = { if (!saving) confirmExit = false }) {
            GlassCard {
                Text("有未保存的布局更改", color = tokens.primaryText)
                GlassButton("保存并返回", { save(exitAfter = true) }, enabled = !saving)
                GlassButton("放弃并返回", onExit, emphasized = false, enabled = !saving)
                GlassButton("继续编辑", { confirmExit = false }, emphasized = false, enabled = !saving)
            }
        }
    }
}

// Original procedural block scene; no Minecraft assets, screenshots or game files.
@Composable
private fun SimulatedScene() {
    val tokens = LocalGlassTokens.current
    Canvas(Modifier.fillMaxSize()) {
        for (row in 0..4) {
            val y = size.height * (.48f + row * .115f)
            val tile = size.width / 9f
            for (column in 0..9) {
                drawRect(tokens.secondaryText.copy(alpha = if ((row + column) % 2 == 0) .045f else .08f),
                    topLeft = Offset(column * tile, y), size = Size(tile - 2, size.height * .11f))
            }
        }
        drawRect(tokens.secondaryText.copy(alpha = .12f), Offset(size.width * .38f, size.height * .33f), Size(size.width * .13f, size.height * .22f))
        drawRect(tokens.secondaryText.copy(alpha = .08f), Offset(size.width * .51f, size.height * .38f), Size(size.width * .09f, size.height * .17f))
        drawLine(tokens.accent, center - Offset(8f, 0f), center + Offset(8f, 0f), strokeWidth = 2f)
        drawLine(tokens.accent, center - Offset(0f, 8f), center + Offset(0f, 8f), strokeWidth = 2f)
    }
}
