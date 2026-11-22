package com.kalibox.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kalibox.app.MainViewModel
import com.kalibox.app.data.DesktopSetup
import com.kalibox.app.service.DesktopController
import com.kalibox.app.service.DesktopState
import com.kalibox.app.util.Constants

/**
 * Desktop entry screen. The desktop is shown in the browser (Chrome) because that renders reliably
 * on every phone; the in-app WebView is kept as an optional "embedded view".
 */
@Composable
fun DesktopScreen(vm: MainViewModel, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val state by DesktopController.state.collectAsState()
    val err by DesktopController.error.collectAsState()
    var embedded by remember { mutableStateOf(false) }
    var autoOpened by remember { mutableStateOf(false) }

    fun openBrowser() {
        val pw = Uri.encode(vm.prefs.vncPassword ?: "")
        val u = Uri.parse("http://127.0.0.1:${Constants.NOVNC_PORT}/vnc.html?autoconnect=true&resize=scale&reconnect=true&password=$pw")
        val chrome = android.content.Intent(android.content.Intent.ACTION_VIEW, u).setPackage("com.android.chrome")
        try {
            ctx.startActivity(chrome)
        } catch (_: Exception) {
            try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)) } catch (_: Exception) { }
        }
    }

    LaunchedEffect(state) {
        if (state == DesktopState.RUNNING && !autoOpened && !embedded) {
            autoOpened = true
            openBrowser()
        }
    }

    if (embedded) {
        EmbeddedDesktop(vm) { embedded = false }
        return
    }

    BackHandler { onExit() }
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            DesktopState.RUNNING -> {
                Text("Kali desktop is running", color = Color.White, fontSize = 20.sp)
                Text(
                    "It opens in Chrome. Use landscape. Tap the small arrow on the left edge of the page for the noVNC panel (keyboard, full screen).",
                    color = Color(0xCCFFFFFF), fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp),
                )
                Button(onClick = { openBrowser() }, Modifier.fillMaxWidth()) { Text("Open desktop in Chrome") }
                OutlinedButton(onClick = { embedded = true }, Modifier.fillMaxWidth()) { Text("Embedded view (experimental)") }
                OutlinedButton(onClick = { vm.stopDesktop() }, Modifier.fillMaxWidth()) { Text("Stop desktop") }
                TextButton(onClick = onExit) { Text("Back") }
            }
            DesktopState.ERROR -> {
                Text(err ?: "The desktop failed to start.", color = Color.White)
                Button(onClick = { vm.onStartDesktop() }) { Text("Retry") }
                OutlinedButton(onClick = onExit) { Text("Back") }
            }
            DesktopState.STOPPED -> {
                Text("Desktop is stopped.", color = Color.White)
                Button(onClick = { vm.onStartDesktop() }) { Text("Start") }
                OutlinedButton(onClick = onExit) { Text("Back") }
            }
            DesktopState.STARTING -> {
                CircularProgressIndicator()
                Text("Starting Kali desktop… (first start can take a minute)", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                OutlinedButton(onClick = onExit, Modifier.padding(top = 12.dp)) { Text("Back") }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedDesktop(vm: MainViewModel, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val state by DesktopController.state.collectAsState()
    val err by DesktopController.error.collectAsState()
    var web by remember { mutableStateOf<WebView?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var hold by remember { mutableStateOf(false) }
    var pad by remember { mutableStateOf(vm.prefs.touchpad) }
    var barOpen by remember { mutableStateOf(true) }
    val js = remember { DesktopSetup.readAsset(ctx, "js/kb.js") }

    BackHandler { onExit() } // leaves the desktop running; use ✕ to stop it

    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        val c = WindowCompat.getInsetsController(window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { c.show(WindowInsetsCompat.Type.systemBars()) }
    }

    fun evalJs(script: String) { web?.evaluateJavascript(script, null) }
    fun sendKey(k: String, code: String, kc: Int, mode: String = "press") = evalJs("kbKey('$k','$code',$kc,'$mode')")

    LaunchedEffect(state, web) {
        val w = web ?: return@LaunchedEffect
        if (state == DesktopState.RUNNING) {
            val pw = Uri.encode(vm.prefs.vncPassword ?: "")
            // let the WebView get its real size first: noVNC computes its scale from the container size
            w.postDelayed({
                w.loadUrl("http://127.0.0.1:${Constants.NOVNC_PORT}/vnc.html?autoconnect=true&resize=scale&reconnect=true&reconnect_delay=1500&password=$pw")
            }, 700)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.setSupportZoom(false)
                    settings.useWideViewPort = true
                    keepScreenOn = true
                    setBackgroundColor(android.graphics.Color.BLACK)
                    // some devices draw noVNC's <canvas> as blank with the GPU layer
                    setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, url: String?) {
                            v.evaluateJavascript(js, null)
                            v.evaluateJavascript("kbSetTouchpad(${vm.prefs.touchpad})", null)
                            // force noVNC to recompute its scale once the layout has settled
                            for (t in longArrayOf(1500, 4000, 8000)) {
                                v.postDelayed({ v.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null) }, t)
                            }
                        }
                        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                            if (r.isForMainFrame && DesktopController.state.value == DesktopState.RUNNING) {
                                v.postDelayed({ v.reload() }, 2000)
                            }
                        }
                    }
                    web = this
                }
            },
        )

        if (state != DesktopState.RUNNING) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state == DesktopState.ERROR) {
                    Text(err ?: "The desktop failed to start.", color = Color.White)
                    Button(onClick = { vm.onStartDesktop() }) { Text("Retry") }
                    OutlinedButton(onClick = onExit) { Text("Back") }
                } else if (state == DesktopState.STOPPED) {
                    Text("Desktop is stopped.", color = Color.White)
                    Button(onClick = { vm.onStartDesktop() }) { Text("Start") }
                    OutlinedButton(onClick = onExit) { Text("Back") }
                } else {
                    CircularProgressIndicator()
                    Text("Starting Kali desktop… (first start can take a minute)", color = Color.White)
                }
            }
        }

        // ---- toolbar
        Column(Modifier.align(Alignment.TopCenter)) {
            if (barOpen) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Color(0xCC000000)),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Tb("Esc") { sendKey("Escape", "Escape", 27) }
                    Tb("Ctrl", ctrl) { ctrl = !ctrl; sendKey("Control", "ControlLeft", 17, if (ctrl) "down" else "up") }
                    Tb("Alt", alt) { alt = !alt; sendKey("Alt", "AltLeft", 18, if (alt) "down" else "up") }
                    Tb("Tab") { sendKey("Tab", "Tab", 9) }
                    Tb("Super") { sendKey("Meta", "MetaLeft", 91) }
                    Tb("⌨") {
                        evalJs("var b=document.getElementById('noVNC_keyboard_button'); if(b){b.click();}")
                        web?.requestFocus()
                        web?.let { (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(it, 0) }
                    }
                    Tb("Touchpad", pad) { pad = !pad; vm.prefs.touchpad = pad; evalJs("kbSetTouchpad($pad)") }
                    Tb("Hold", hold) { hold = !hold; evalJs("kbHold($hold)") }
                    Tb("Browser") {
                        val pw = Uri.encode(vm.prefs.vncPassword ?: "")
                        val u = "http://127.0.0.1:${Constants.NOVNC_PORT}/vnc.html?autoconnect=true&resize=scale&password=$pw"
                        try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) { }
                    }
                    Tb("⌂") { onExit() }
                    Tb("✕ Stop") { vm.stopDesktop() }
                }
            }
            TextButton(onClick = { barOpen = !barOpen }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (barOpen) "▲" else "▼", color = Color(0x99FFFFFF), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Tb(label: String, on: Boolean = false, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    if (on) Button(onClick = onClick, contentPadding = pad) { Text(label, fontSize = 12.sp) }
    else OutlinedButton(onClick = onClick, contentPadding = pad) { Text(label, fontSize = 12.sp, color = Color.White) }
}
