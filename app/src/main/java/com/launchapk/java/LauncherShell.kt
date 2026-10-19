package com.launchapk.java

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.launchapk.java.auth.OfflineAccountStore
import com.launchapk.java.auth.OfflineAccounts
import com.launchapk.java.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LauncherPage(val title: String) {
    START("启动"), DOWNLOADS("下载"), SETTINGS("设置"), MORE("更多"),
}

@Composable
internal fun LauncherShell(
    preferences: GlassPreferences,
    onPreferencesChange: (GlassPreferences) -> Unit,
    requestNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current
    val store = remember(context) { OfflineAccountStore(context.applicationContext.filesDir) }
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(OfflineAccounts()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailure by remember { mutableStateOf<String?>(null) }
    var operationFailure by remember { mutableStateOf<String?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf(LauncherPage.START.name) }
    var accountSettings by rememberSaveable { mutableStateOf(false) }
    val pageStates = rememberSaveableStateHolder()

    LaunchedEffect(store, loadAttempt) {
        loading = true
        loadFailure = null
        try { accounts = withContext(Dispatchers.IO) { store.load() }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { loadFailure = "读取离线账户失败：${e.message}。原文件已保留。"
        } finally { loading = false }
    }
    fun updateAccounts(onSuccess: () -> Unit = {}, transform: (OfflineAccounts) -> OfflineAccounts) {
        if (loading || loadFailure != null) return
        loading = true
        operationFailure = null
        scope.launch {
            try {
                accounts = withContext(Dispatchers.IO) { store.update(transform) }
                onSuccess()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { operationFailure = "保存账户失败：${e.message}"
            } finally { loading = false }
        }
    }
    fun manageAccounts() {
        accountSettings = true
        page = LauncherPage.SETTINGS.name
    }
    fun openControls() { context.startActivity(Intent(context, ControlDebugActivity::class.java)) }
    BackHandler(enabled = page != LauncherPage.START.name) { page = LauncherPage.START.name }

    GlassBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.app_name), modifier = Modifier.weight(1f),
                    color = tokens.primaryText, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Row(Modifier.weight(2f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LauncherPage.entries.forEach { item ->
                        GlassButton(item.title, { page = item.name }, emphasized = page == item.name)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(page) {
                    GlassReveal {
                        pageStates.SaveableStateProvider(page) {
                            when (LauncherPage.valueOf(page)) {
                                LauncherPage.START -> StartPage(accounts, loading, loadFailure,
                                    onManage = ::manageAccounts,
                                    onDownload = { page = LauncherPage.DOWNLOADS.name }, onControls = ::openControls)
                                LauncherPage.DOWNLOADS -> DownloadsScreen(
                                    onExit = { page = LauncherPage.START.name }, requestNotifications = requestNotifications, embedded = true)
                                LauncherPage.SETTINGS -> SettingsPage(preferences, onPreferencesChange,
                                    accounts, loading, loadFailure ?: operationFailure, loadFailure == null,
                                    accountSettings, { accountSettings = it }, { loadAttempt++ },
                                    onAdd = { name, onCreated -> updateAccounts(onCreated) { it.add(name) } },
                                    onSelect = { uuid -> updateAccounts { it.select(uuid) } },
                                    onRemove = { uuid -> updateAccounts { it.remove(uuid) } })
                                LauncherPage.MORE -> MorePage(::openControls)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartPage(
    accounts: OfflineAccounts,
    loading: Boolean,
    loadFailure: String?,
    onManage: () -> Unit,
    onDownload: () -> Unit,
    onControls: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val context = LocalContext.current
    var offline by rememberSaveable { mutableStateOf(true) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(0.38f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("正版", { offline = false }, emphasized = !offline)
                    GlassButton("离线", { offline = true }, emphasized = offline)
                }
                Spacer(Modifier.height(12.dp))
                if (offline) {
                    Box(Modifier.size(56.dp).background(tokens.accent.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center) {
                        Text(accounts.selected?.name?.take(1)?.uppercase() ?: "?", color = tokens.accent,
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(if (loading) "正在读取账户…" else accounts.selected?.name ?: "未添加离线账户",
                        color = tokens.primaryText, style = MaterialTheme.typography.titleMedium)
                    Text(if (loadFailure != null) "账户读取失败，请前往账户管理重试" else "离线账户 · 本地档案",
                        color = tokens.secondaryText, style = MaterialTheme.typography.bodySmall)
                    GlassButton(if (accounts.accounts.isEmpty()) "添加离线账户" else "管理离线账户",
                        onManage, modifier = Modifier.fillMaxWidth(), emphasized = false)
                } else {
                    MicrosoftAccountPanel()
                }
            }
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                GlassButton("选择版本并启动", {
                    context.startActivity(Intent(context, LaunchPreparationActivity::class.java).putExtra("offline", offline))
                }, modifier = Modifier.fillMaxWidth())
                Text("检查已下载版本、离线档案、Java 要求与缺失组件", color = tokens.secondaryText,
                    style = MaterialTheme.typography.bodySmall)
                GlassButton("运行环境", { context.startActivity(Intent(context, RuntimeActivity::class.java)) }, emphasized = false,
                    modifier = Modifier.fillMaxWidth())
            }
        }
        Column(Modifier.weight(0.62f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("准备你的 Java 世界", color = tokens.primaryText,
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.home_subtitle), color = tokens.secondaryText)
                Text("游戏版本", color = tokens.primaryText, style = MaterialTheme.typography.titleMedium)
                Text("前往下载分类获取游戏版本、模组、整合包、光影或材质包，并管理下载任务。",
                    color = tokens.secondaryText)
                GlassButton("打开下载中心", onDownload)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("虚拟按键", color = tokens.primaryText, style = MaterialTheme.typography.titleMedium)
                Text("在模拟场景中编辑布局，测试按键、摇杆和视角滑动。", color = tokens.secondaryText)
                GlassButton("调试按键", onControls, emphasized = false)
            }
        }
    }
}

@Composable
private fun SettingsPage(
    preferences: GlassPreferences,
    onPreferencesChange: (GlassPreferences) -> Unit,
    accounts: OfflineAccounts,
    loading: Boolean,
    error: String?,
    readable: Boolean,
    accountSettings: Boolean,
    onSection: (Boolean) -> Unit,
    onReload: () -> Unit,
    onAdd: (String, () -> Unit) -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val tokens = LocalGlassTokens.current
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(150.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton("外观与性能", { onSection(false) }, emphasized = !accountSettings, modifier = Modifier.fillMaxWidth())
            GlassButton("账户管理", { onSection(true) }, emphasized = accountSettings, modifier = Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (accountSettings) {
                GlassCard(Modifier.fillMaxWidth()) { MicrosoftAccountPanel() }
                OfflineAccountManager(accounts, loading, error, readable, onReload, onAdd, onSelect, onRemove)
            } else {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("外观与性能", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
                    AppearanceToggle(
                        title = stringResource(R.string.disable_blur),
                        description = stringResource(when {
                            Build.VERSION.SDK_INT < 31 -> R.string.blur_unsupported
                            preferences.reduceTransparency -> R.string.blur_reduced
                            else -> R.string.blur_description
                        }),
                        checked = !preferences.blurEnabled || preferences.reduceTransparency || Build.VERSION.SDK_INT < 31,
                        enabled = Build.VERSION.SDK_INT >= 31 && !preferences.reduceTransparency,
                        onCheckedChange = { onPreferencesChange(preferences.copy(blurEnabled = !it)) },
                    )
                    AppearanceToggle(stringResource(R.string.reduce_transparency),
                        stringResource(R.string.transparency_description), preferences.reduceTransparency,
                        onCheckedChange = { onPreferencesChange(preferences.copy(reduceTransparency = it)) })
                    Text(stringResource(R.string.theme_description), color = tokens.secondaryText)
                }
            }
        }
    }
}

@Composable
private fun MorePage(onControls: () -> Unit) {
    val tokens = LocalGlassTokens.current
    val context = LocalContext.current
    var about by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Text("工具", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
            Text("编辑和测试游戏内虚拟按键布局", color = tokens.secondaryText)
            GlassButton("调试按键", onControls)
            GlassButton("运行环境", { context.startActivity(Intent(context, RuntimeActivity::class.java)) }, emphasized = false)
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("关于", color = tokens.primaryText, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.distribution_note), color = tokens.secondaryText)
            GlassButton(stringResource(R.string.about_title), { about = true }, emphasized = false)
        }
    }
    if (about) GlassDialog(stringResource(R.string.app_name), stringResource(R.string.about_message),
        stringResource(R.string.close), { about = false })
}

@Composable
private fun AppearanceToggle(
    title: String, description: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit,
) {
    val tokens = LocalGlassTokens.current
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = tokens.primaryText, style = MaterialTheme.typography.bodyLarge)
            Text(description, color = tokens.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled, colors = SwitchDefaults.colors(
            checkedTrackColor = tokens.accent, checkedThumbColor = tokens.onAccent,
            uncheckedTrackColor = tokens.secondaryText.copy(alpha = 0.12f), uncheckedThumbColor = tokens.secondaryText,
            uncheckedBorderColor = tokens.secondaryText.copy(alpha = 0.35f)))
    }
}
