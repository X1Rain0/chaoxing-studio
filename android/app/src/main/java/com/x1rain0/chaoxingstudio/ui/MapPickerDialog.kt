package com.x1rain0.chaoxingstudio.ui

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Color as AndroidColor
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Button
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale

@Composable
internal fun MapPickerDialog(
    initialLatitude: String,
    initialLongitude: String,
    onDismiss: () -> Unit,
    onUse: (latitude: String, longitude: String) -> Unit,
) {
    val context = LocalContext.current
    val initialLat = remember(initialLatitude) { validLatitude(initialLatitude) }
    val initialLng = remember(initialLongitude) { validLongitude(initialLongitude) }
    val initialPointExists = initialLat != null && initialLng != null
    var latitude by rememberSaveable(initialLatitude, initialLongitude) {
        mutableStateOf(initialLat?.let(::formatCoordinate).orEmpty())
    }
    var longitude by rememberSaveable(initialLatitude, initialLongitude) {
        mutableStateOf(initialLng?.let(::formatCoordinate).orEmpty())
    }
    var pointSelected by rememberSaveable(initialLatitude, initialLongitude) { mutableStateOf(initialPointExists) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 720.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("地图选点", style = MaterialTheme.typography.titleLarge)
                        Text("高德地图 · GCJ-02", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭地图")
                    }
                }

                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                    factory = {
                        createMapWebView(
                            context = context,
                            initialLatitude = initialLat ?: DEFAULT_LATITUDE,
                            initialLongitude = initialLng ?: DEFAULT_LONGITUDE,
                            hasInitialPoint = initialPointExists,
                            onPoint = { lat, lng ->
                                latitude = formatCoordinate(lat)
                                longitude = formatCoordinate(lng)
                                pointSelected = true
                            },
                        )
                    },
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.removeJavascriptInterface("AndroidMap")
                        webView.destroy()
                    },
                )

                Text(
                    text = if (pointSelected) "$latitude, $longitude" else "点击地图放置位置标记",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (pointSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "拖动浏览 · 使用右上角按钮缩放 · 无需定位权限或高德 Key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = { onUse(latitude, longitude) },
                        enabled = pointSelected,
                    ) { Text("使用此位置") }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createMapWebView(
    context: android.content.Context,
    initialLatitude: Double,
    initialLongitude: Double,
    hasInitialPoint: Boolean,
    onPoint: (Double, Double) -> Unit,
): WebView = WebView(context).apply {
    setBackgroundColor(AndroidColor.rgb(247, 244, 237))
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    addJavascriptInterface(MapCoordinateBridge(onPoint), "AndroidMap")
    val debugWebView = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            return request.isForMainFrame && uri.host != APP_ASSET_HOST
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val target = view ?: return
            target.post {
                val density = target.resources.displayMetrics.density
                val mapHeightDp = if (target.height > 0) target.height / density else 340f
                if (debugWebView) Log.d(MAP_PICKER_TAG, "page finished: $url, view=${target.width}x${target.height}, mapHeightDp=$mapHeightDp")
                val pointFlag = if (hasInitialPoint) "true" else "false"
                target.evaluateJavascript(
                    "window.initMap(${initialLatitude},${initialLongitude},$pointFlag,$mapHeightDp)",
                ) { result ->
                    if (debugWebView) Log.d(MAP_PICKER_TAG, "initMap result: $result")
                }
                if (debugWebView) {
                    target.evaluateJavascript(
                        "(function(){var m=document.getElementById('map');var h=document.getElementById('hint');return JSON.stringify({ready:document.readyState,title:document.title,body:document.body&&document.body.innerText,mapChildren:m&&m.children.length,mapRect:m&&m.getBoundingClientRect().toJSON(),hint:h&&h.textContent,innerHeight:window.innerHeight})})()",
                    ) { result -> Log.d(MAP_PICKER_TAG, "dom: $result") }
                }
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (debugWebView && request?.isForMainFrame == true) {
                Log.e(MAP_PICKER_TAG, "main-frame load error ${error?.errorCode}: ${error?.description}")
            }
        }
    }
    if (debugWebView) {
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(MAP_PICKER_TAG, "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                }
                return true
            }
        }
    }
    val html = context.assets.open("map_picker.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    loadDataWithBaseURL(
        "https://appassets.androidplatform.net/",
        html,
        "text/html",
        "UTF-8",
        null,
    )
}

private class MapCoordinateBridge(private val onPoint: (Double, Double) -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onPoint(latitude: String, longitude: String) {
        val lat = validLatitude(latitude) ?: return
        val lng = validLongitude(longitude) ?: return
        mainHandler.post { onPoint(lat, lng) }
    }
}

private fun validLatitude(value: String): Double? =
    value.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in -85.05..85.05 }

private fun validLongitude(value: String): Double? =
    value.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }

private fun formatCoordinate(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

private const val DEFAULT_LATITUDE = 39.9042
private const val DEFAULT_LONGITUDE = 116.4074
private const val APP_ASSET_HOST = "appassets.androidplatform.net"
private const val MAP_PICKER_TAG = "MapPicker"
