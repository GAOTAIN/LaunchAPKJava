package com.launchapk.java

import android.annotation.SuppressLint
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.launchapk.java.core.*
import com.launchapk.java.ui.*

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ContentBrowser(initialUrl: String, kind: ContentKind, onClose: () -> Unit,
    onReadPage: (String) -> Unit, onDownload: (DownloadSpec) -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var page by remember { mutableStateOf(initialUrl) }
    var message by remember { mutableStateOf("原网页浏览；文件下载前会显示来源确认") }
    var pending by remember { mutableStateOf<DownloadSpec?>(null) }
    val tokens = LocalGlassTokens.current
    BackHandler { if (web?.canGoBack() == true) web?.goBack() else onClose() }
    DisposableEffect(Unit) { onDispose { web?.stopLoading(); web?.destroy(); web = null } }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("关闭网页", onClose, emphasized = false)
            GlassButton("读取当前资料页", { onReadPage(page) }, enabled = runCatching { mcmodPage(page) }.isSuccess)
        }
        Text(message, color = tokens.secondaryText, modifier = Modifier.padding(horizontal = 12.dp), maxLines = 2)
        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
            WebView(context).apply {
                web = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.safeBrowsingEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        val valid = runCatching { httpsUri(request.url.toString()) }.isSuccess
                        if (!valid) message = "此链接不能在下载页打开；仅支持 HTTPS"
                        return !valid
                    }
                    override fun onPageFinished(view: WebView, url: String) { page = url }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) message = "网页加载失败：${error.description}"
                    }
                }
                setDownloadListener { url, _, disposition, mime, _ ->
                    runCatching {
                        httpsUri(url)
                        val filename = safeFilename(URLUtil.guessFileName(url, disposition, mime))
                        require(kind.accepts(filename)) { "当前分类不支持该文件；请选择对应分类或在原站操作" }
                        DownloadSpec("content", filename, url,
                            "content/${kind.directory}/web/${digestText(url).take(20)}/$filename", source = page)
                    }.onSuccess { pending = it }.onFailure { message = it.message ?: "无法建立下载任务" }
                }
                loadUrl(initialUrl)
            }
        })
    }
    pending?.let { spec ->
        Dialog(onDismissRequest = { pending = null }) {
            GlassCard {
                Text("下载 ${spec.label}？", color = tokens.primaryText)
                Text("来源：${httpsUri(spec.url).host}；原站未提供哈希。本次仅下载原文件，不自动安装。", color = tokens.secondaryText)
                GlassButton("确认下载", { pending = null; onDownload(spec) })
                GlassButton("取消", { pending = null }, emphasized = false)
            }
        }
    }
}
