package com.launchapk.java

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.launchapk.java.auth.OfflineAccounts
import com.launchapk.java.ui.*

@Composable
internal fun OfflineAccountManager(
    accounts: OfflineAccounts,
    busy: Boolean,
    error: String?,
    readable: Boolean,
    onReload: () -> Unit,
    onAdd: (String, () -> Unit) -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val tokens = LocalGlassTokens.current
    var nickname by rememberSaveable { mutableStateOf("") }
    var deleteUuid by rememberSaveable { mutableStateOf<String?>(null) }
    val deleteAccount = accounts.accounts.firstOrNull { it.uuid == deleteUuid }
    GlassCard(Modifier.fillMaxWidth()) {
        Text("离线账户", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
        Text("昵称与当前选择自动保存在本机。离线账户不含微软登录凭据或正版授权信息。",
            color = tokens.secondaryText)
        if (error != null) {
            Text(error, color = tokens.primaryText)
            if (!readable) GlassButton("重新读取", onReload, enabled = !busy, emphasized = false)
        }
        OutlinedTextField(nickname, { nickname = it.take(64) }, label = { Text("游戏昵称") },
            supportingText = { Text("3–16 位英文字母、数字或下划线；大小写影响 UUID") },
            singleLine = true, enabled = !busy && readable,
            modifier = Modifier.fillMaxWidth())
        GlassButton(if (busy) "正在处理…" else "添加并选用", { onAdd(nickname) { nickname = "" } },
            enabled = !busy && readable && nickname.isNotBlank() && accounts.accounts.size < 32)
        Text("已保存 ${accounts.accounts.size}/32 个离线账户", color = tokens.secondaryText,
            style = MaterialTheme.typography.bodySmall)
    }
    accounts.accounts.forEach { account ->
        key(account.uuid) {
            GlassCard(Modifier.fillMaxWidth(), active = accounts.selectedUuid == account.uuid) {
                Text(account.name + if (accounts.selectedUuid == account.uuid) " · 当前使用" else "",
                    color = tokens.primaryText, style = MaterialTheme.typography.titleMedium)
                Text("离线 · ${account.uuid}", color = tokens.secondaryText, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("选用", { onSelect(account.uuid) },
                        enabled = !busy && readable && accounts.selectedUuid != account.uuid)
                    GlassButton("删除", { deleteUuid = account.uuid }, emphasized = false, enabled = !busy && readable)
                }
            }
        }
    }
    if (deleteAccount != null) {
        Dialog(onDismissRequest = { deleteUuid = null }) {
            GlassCard {
                Text("删除离线账户？", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
                Text("将删除 ${deleteAccount.name} 的本地账户记录。游戏文件与存档会保留。", color = tokens.secondaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("取消", { deleteUuid = null }, emphasized = false)
                    GlassButton("删除", { onRemove(deleteAccount.uuid); deleteUuid = null }, enabled = !busy && readable)
                }
            }
        }
    }
}
