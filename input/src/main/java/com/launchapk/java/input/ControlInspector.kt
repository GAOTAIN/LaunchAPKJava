package com.launchapk.java.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.launchapk.java.ui.GlassButton
import com.launchapk.java.ui.GlassCard
import com.launchapk.java.ui.LocalGlassTokens

@Composable
internal fun ControlInspector(layout: ControlLayout, id: String, onChange: (ControlLayout) -> Unit, onDismiss: () -> Unit) {
    val button = layout.buttons.firstOrNull { it.id == id }
    val stick = layout.joysticks.firstOrNull { it.id == id }
    val tokens = LocalGlassTokens.current
    var label by remember(id) { mutableStateOf(button?.label.orEmpty()) }
    var code by remember(id) { mutableStateOf(button?.keyCode?.toString().orEmpty()) }
    val key = code.toIntOrNull()
    val validKey = key != null && (key in 0..348 || key in -2..-1)
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (button != null) "按键属性" else "摇杆属性", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
                if (button != null) {
                    OutlinedTextField(label, { label = it.take(32) }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(code, { code = it.take(5) }, label = { Text("GLFW 键值；左键 -1，右键 -2") },
                        isError = !validKey, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("W=87 A=65 S=83 D=68 空格=32 Shift=340", color = tokens.secondaryText, style = MaterialTheme.typography.bodySmall)
                    Text("宽度", color = tokens.primaryText)
                    Slider(button.width, { value -> onChange(layout.copy(buttons = layout.buttons.map {
                        if (it.id == id) it.copy(width = value, x = it.x.coerceAtMost(1f - value)) else it
                    })) }, valueRange = .04f.. .5f)
                    Text("高度", color = tokens.primaryText)
                    Slider(button.height, { value -> onChange(layout.copy(buttons = layout.buttons.map {
                        if (it.id == id) it.copy(height = value, y = it.y.coerceAtMost(1f - value)) else it
                    })) }, valueRange = .06f.. .5f)
                    Text("不透明度 ${(button.alpha * 100).toInt()}%", color = tokens.primaryText)
                    Slider(button.alpha, { value -> onChange(layout.copy(buttons = layout.buttons.map { if (it.id == id) it.copy(alpha = value) else it })) }, valueRange = .15f..1f)
                }
                if (stick != null) {
                    Text("半径；摇杆映射 WASD，支持斜向移动", color = tokens.secondaryText)
                    Slider(stick.radius, { value -> onChange(layout.copy(joysticks = layout.joysticks.map { if (it.id == id) it.copy(radius = value) else it })) }, valueRange = .06f.. .3f)
                }
                GlassButton("应用", onClick = {
                    if (button != null && validKey) onChange(layout.copy(buttons = layout.buttons.map { if (it.id == id) it.copy(label = label.trim(), keyCode = key!!) else it }))
                    onDismiss()
                }, enabled = button == null || validKey && label.isNotBlank(), modifier = Modifier.fillMaxWidth())
                GlassButton("删除控件", onClick = {
                    onChange(layout.copy(buttons = layout.buttons.filterNot { it.id == id }, joysticks = layout.joysticks.filterNot { it.id == id }))
                    onDismiss()
                }, emphasized = false, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
