package com.kalibox.app.ui

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kalibox.app.MainViewModel
import com.kalibox.app.service.SessionHub
import com.termux.view.TerminalView

@Composable
fun TerminalScreen(vm: MainViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val sessions by SessionHub.sessions.collectAsState()
    val active by SessionHub.active.collectAsState()
    val tick by SessionHub.tick.collectAsState()
    val fontSp by SessionHub.fontSp.collectAsState()
    val ctrl by SessionHub.ctrl.collectAsState()
    val alt by SessionHub.alt.collectAsState()
    @Suppress("UNUSED_VARIABLE") val titleVersion = tick // subscribes this screen to title changes

    LaunchedEffect(Unit) { SessionHub.onNewTabRequest = { vm.newSession() } }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().background(Color.Black)) {
        // ---- tabs
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surface)) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("⌂") }
            sessions.forEachIndexed { i, s ->
                val title = (s.title?.takeIf { it.isNotBlank() } ?: "bash").take(18)
                val sel = i == active
                if (sel) Button(onClick = { SessionHub.active.value = i }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("${i + 1}:$title", fontSize = 12.sp, maxLines = 1) }
                else OutlinedButton(onClick = { SessionHub.active.value = i }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("${i + 1}:$title", fontSize = 12.sp, maxLines = 1) }
                TextButton(onClick = { SessionHub.close(i) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("✕", fontSize = 12.sp) }
            }
            TextButton(onClick = { vm.newSession() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("＋") }
        }

        // ---- terminal
        if (sessions.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("No open terminal.", color = Color.White)
                Button(onClick = { vm.newSession() }) { Text("New session") }
            }
        } else {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { c ->
                    TerminalView(c, null).apply {
                        setTerminalViewClient(SessionHub.viewClient)
                        setTypeface(Typeface.MONOSPACE)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        SessionHub.view = this
                    }
                },
                update = { v ->
                    SessionHub.view = v
                    v.setTextSize((fontSp * v.resources.displayMetrics.density).toInt())
                    val s = sessions.getOrNull(active)
                    if (s != null && v.currentSession !== s) v.attachSession(s)
                    v.onScreenUpdated()
                },
            )
        }

        // ---- extra keys
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surface),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            KeyBtn("Esc") { SessionHub.send("\u001b") }
            KeyBtn("Ctrl", on = ctrl) { SessionHub.ctrl.value = !ctrl }
            KeyBtn("Alt", on = alt) { SessionHub.alt.value = !alt }
            KeyBtn("Tab") { SessionHub.send("\t") }
            KeyBtn("←") { SessionHub.send("\u001b[D") }
            KeyBtn("↑") { SessionHub.send("\u001b[A") }
            KeyBtn("↓") { SessionHub.send("\u001b[B") }
            KeyBtn("→") { SessionHub.send("\u001b[C") }
            KeyBtn("|") { SessionHub.send("|") }
            KeyBtn("-") { SessionHub.send("-") }
            KeyBtn("/") { SessionHub.send("/") }
            KeyBtn("~") { SessionHub.send("~") }
            KeyBtn("Home") { SessionHub.send("\u001b[H") }
            KeyBtn("End") { SessionHub.send("\u001b[F") }
            KeyBtn("PgUp") { SessionHub.send("\u001b[5~") }
            KeyBtn("PgDn") { SessionHub.send("\u001b[6~") }
            KeyBtn("Paste") { SessionHub.paste() }
        }
    }
}

@Composable
private fun KeyBtn(label: String, on: Boolean = false, onClick: () -> Unit) {
    val colors = if (on) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
    if (on) Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp), colors = colors) { Text(label, fontSize = 13.sp) }
    else OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label, fontSize = 13.sp) }
}
