package com.kalibox.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.InputMethodManager
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
import com.kalibox.app.service.DesktopController
import com.kalibox.app.service.DesktopState
import com.kalibox.app.util.Constants

/** Desktop screen: shows the Kali desktop inside the app with a native VNC viewer (no WebView, no browser). */
@Composable
fun DesktopScreen(vm: MainViewModel, onExit: () -> Unit) {
    val state by DesktopController.state.collectAsState()
    val err by DesktopController.error.collectAsState()

    if (state == DesktopState.RUNNING) {
        NativeDesktop(vm, onExit)
        return
    }

    BackHandler { onExit() }
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
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
            else -> {
                CircularProgressIndicator()
                Text("Starting Kali desktop… (first start can take a minute)", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                OutlinedButton(onClick = onExit, Modifier.padding(top = 12.dp)) { Text("Back") }
            }
        }
    }
}

@Composable
fun NativeDesktop(vm: MainViewModel, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var vnc by remember { mutableStateOf<VncView?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var hold by remember { mutableStateOf(false) }
    var pad by remember { mutableStateOf(vm.prefs.touchpad) }
    var barOpen by remember { mutableStateOf(true) }

    BackHandler { onExit() } // leaves the desktop running; use Stop to end it

    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        val c = WindowCompat.getInsetsController(window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { c.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(vnc) {
        val current = vnc
        onDispose { current?.stop() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                VncView(c).also { v ->
                    v.touchpad = vm.prefs.touchpad
                    v.start(vm.prefs.vncPassword ?: "")
                    vnc = v
                }
            },
        )

        Column(Modifier.align(Alignment.TopCenter)) {
            if (barOpen) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Color(0xCC000000)),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Tb("Esc") { vnc?.client?.press(0xFF1B) }
                    Tb("Ctrl", ctrl) { ctrl = !ctrl; vnc?.client?.key(0xFFE3, ctrl) }
                    Tb("Alt", alt) { alt = !alt; vnc?.client?.key(0xFFE9, alt) }
                    Tb("Tab") { vnc?.client?.press(0xFF09) }
                    Tb("Super") { vnc?.client?.press(0xFFEB) }
                    Tb("Left") { vnc?.client?.press(0xFF51) }
                    Tb("Up") { vnc?.client?.press(0xFF52) }
                    Tb("Down") { vnc?.client?.press(0xFF54) }
                    Tb("Right") { vnc?.client?.press(0xFF53) }
                    Tb("Keyboard") {
                        vnc?.let { v ->
                            v.requestFocus()
                            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
                        }
                    }
                    Tb(if (pad) "Mode: Touchpad" else "Mode: Direct touch", pad) {
                        pad = !pad
                        vm.prefs.touchpad = pad
                        vnc?.touchpad = pad
                    }
                    Tb("Hold", hold) { hold = !hold; vnc?.holdLeft = hold }
                    Tb("Browser") {
                        val pw = Uri.encode(vm.prefs.vncPassword ?: "")
                        val u = "http://127.0.0.1:${Constants.NOVNC_PORT}/vnc.html?autoconnect=true&resize=scale&password=$pw"
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) { }
                    }
                    Tb("Home") { onExit() }
                    Tb("Stop") { vm.stopDesktop() }
                }
            }
            TextButton(
                onClick = { barOpen = !barOpen },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(if (barOpen) "hide" else "menu", color = Color(0x99FFFFFF), fontSize = 12.sp)
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
