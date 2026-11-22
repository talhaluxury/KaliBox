package com.kalibox.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kalibox.app.MainViewModel
import com.kalibox.app.domain.Screen
import com.kalibox.app.domain.Stage
import com.kalibox.app.service.DesktopController
import com.kalibox.app.service.DesktopState
import com.kalibox.app.service.SessionHub
import com.kalibox.app.util.AppLog
import com.kalibox.app.util.Fmt

@Composable
fun Root(vm: MainViewModel) {
    val screen by vm.screen.collectAsState()
    val crash by vm.lastCrash.collectAsState()
    val notice by vm.notice.collectAsState()
    val ctx = LocalContext.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (screen) {
            Screen.LEGAL -> LegalScreen(vm)
            Screen.SETUP -> SetupScreen(vm)
            Screen.HOME -> HomeScreen(vm)
            Screen.TERMINAL -> TerminalScreen(vm) { vm.go(Screen.HOME) }
            Screen.DESKTOP_SETUP -> DesktopSetupScreen(vm)
            Screen.DESKTOP -> DesktopScreen(vm) { vm.go(Screen.HOME) }
            Screen.SETTINGS -> SettingsScreen(vm)
            Screen.TASK -> TaskScreen(vm)
        }
    }

    crash?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.dismissCrash() },
            title = { Text("KaliBox crashed last time") },
            text = { Text("A crash was recorded and saved to the log. You can share the log to get it fixed.\n\n" + text.take(400), fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = { AppLog.shareIntent(ctx)?.let { ctx.startActivity(it) }; vm.dismissCrash() }) { Text("Share logs") }
            },
            dismissButton = { TextButton(onClick = { vm.dismissCrash() }) { Text("Dismiss") } },
        )
    }
    notice?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.consumeNotice() },
            text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(msg, fontSize = 12.sp, modifier = Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { vm.consumeNotice() }) { Text("OK") } },
        )
    }
}

@Composable
fun ScreenColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun InfoCard(title: String, body: String, warn: Boolean = false) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (warn) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ------------------------------------------------------------------ legal
@Composable
fun LegalScreen(vm: MainViewModel) {
    ScreenColumn {
        Text("KaliBox", style = MaterialTheme.typography.headlineMedium)
        Text("Authorized use only", style = MaterialTheme.typography.titleMedium)
        Text(
            "KaliBox gives you a Kali Linux environment on your phone. The tools inside are for security education, " +
                "research and authorized testing ONLY: your own devices and networks, or systems where you have explicit written permission.\n\n" +
                "Using them against systems you do not own or are not authorized to test may be illegal. You are fully responsible for how you use them. " +
                "KaliBox is provided without warranty (GPLv3).",
        )
        Button(onClick = { vm.acceptLegal() }, Modifier.fillMaxWidth()) { Text("I understand and accept") }
    }
}

// ------------------------------------------------------------------ setup / install
@Composable
fun SetupScreen(vm: MainViewModel) {
    val p by vm.install.collectAsState()
    ScreenColumn {
        Text("Set up Kali Linux", style = MaterialTheme.typography.headlineSmall)
        Text("Downloads the Kali arm64 rootfs, verifies it, extracts it and applies proot fixes. Needs 6 GB free storage and a network connection.")
        if (p.running || p.paused || p.error != null || p.stage == Stage.DONE) {
            val stageName = when (p.stage) {
                Stage.CHECK -> "Checking"; Stage.DOWNLOAD -> "Downloading"; Stage.VERIFY -> "Verifying"
                Stage.EXTRACT -> "Extracting"; Stage.CONFIGURE -> "Configuring"; Stage.DONE -> "Done"
            }
            Text("$stageName${if (p.percent >= 0) " — ${p.percent}%" else ""}", style = MaterialTheme.typography.titleMedium)
            if (p.percent >= 0) LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth())
            else if (p.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (p.stage == Stage.DOWNLOAD && p.total > 0) {
                Text("${Fmt.bytes(p.bytes)} / ${Fmt.bytes(p.total)}   ${Fmt.speed(p.speed)}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
            if (p.message.isNotEmpty()) Text(p.message, style = MaterialTheme.typography.bodySmall)
        }
        p.error?.let { InfoCard("Something went wrong", it, warn = true) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.running && p.stage == Stage.DOWNLOAD) {
                OutlinedButton(onClick = { vm.pauseInstall() }) { Text("Pause") }
            }
            if (!p.running) {
                Button(onClick = { vm.startInstall() }) {
                    Text(if (p.paused) "Resume" else if (p.error != null) "Retry" else "Install Kali")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ home
@Composable
fun HomeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val desk by DesktopController.state.collectAsState()
    val sessions by SessionHub.sessions.collectAsState()
    val pm = ctx.getSystemService(PowerManager::class.java)
    var batteryOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }

    ScreenColumn {
        Text("KaliBox", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { if (desk == DesktopState.RUNNING || desk == DesktopState.STARTING) vm.go(Screen.DESKTOP) else vm.onStartDesktop() }, Modifier.fillMaxWidth()) {
            Text(if (desk == DesktopState.RUNNING) "Open Desktop" else "Start Desktop")
        }
        Button(onClick = { vm.openTerminal() }, Modifier.fillMaxWidth()) {
            Text(if (sessions.isEmpty()) "Open Terminal" else "Terminal (${sessions.size} tab${if (sessions.size > 1) "s" else ""})")
        }
        OutlinedButton(onClick = { vm.go(Screen.SETTINGS) }, Modifier.fillMaxWidth()) { Text("Settings & tools") }
        if (desk != DesktopState.STOPPED || sessions.isNotEmpty()) {
            OutlinedButton(onClick = { vm.stopAll() }, Modifier.fillMaxWidth()) { Text("Stop all") }
        }
        if (!batteryOk) {
            InfoCard("Keep KaliBox alive", "Android may kill background processes. Disable battery optimization for KaliBox.")
            Button(onClick = {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                try { ctx.startActivity(i) } catch (_: Exception) { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            }) { Text("Disable battery optimization") }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            InfoCard(
                "Android 12+ tip: phantom process killer",
                "Android 12+ can silently kill child processes (the desktop or long installs). On Android 14+, enable Developer options → " +
                    "\"Disable child process restrictions\". On Android 12–13 use Wireless debugging + adb on the phone itself " +
                    "to run: settings put global settings_enable_monitor_phantom_procs false",
            )
        }
    }
}

// ------------------------------------------------------------------ desktop setup
@Composable
fun DesktopSetupScreen(vm: MainViewModel) {
    BackHandler { vm.go(Screen.HOME) }
    var pw by remember { mutableStateOf("") }
    val warnings = remember { vm.desktopWarnings() }
    ScreenColumn {
        Text("Install Kali Desktop", style = MaterialTheme.typography.headlineSmall)
        Text("Installs XFCE, TigerVNC, noVNC and Firefox ESR (about 2–4 GB). The VNC server only listens on localhost.")
        warnings.forEach { InfoCard("Warning", it, warn = true) }
        OutlinedTextField(
            value = pw, onValueChange = { pw = it.take(8) }, label = { Text("VNC password (6–8 characters)") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )
        Text("Stored encrypted on this device. VNC only uses the first 8 characters.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.installDesktop(pw) }, Modifier.fillMaxWidth()) { Text(if (vm.prefs.desktopInstalled) "Re-run install" else "Install desktop") }
        OutlinedButton(onClick = { vm.go(Screen.HOME) }, Modifier.fillMaxWidth()) { Text("Back") }
    }
}

// ------------------------------------------------------------------ task / log screen
@Composable
fun TaskScreen(vm: MainViewModel) {
    val t by vm.task.collectAsState()
    val list = rememberLazyListState()
    BackHandler(enabled = !t.running) { vm.go(vm.taskReturn) }
    LaunchedEffect(t.lines.size) { if (t.lines.isNotEmpty()) list.scrollToItem(t.lines.lastIndex) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t.title, style = MaterialTheme.typography.titleMedium)
        if (t.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(t.lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
        }
        if (!t.running) {
            Text(
                if (t.exitCode == 0) "✔ Finished successfully" else "✖ Finished with exit code ${t.exitCode}. Check the log above.",
                color = if (t.exitCode == 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
            Button(onClick = { vm.go(vm.taskReturn) }, Modifier.fillMaxWidth()) { Text("Done") }
        } else {
            Text("Keep the app open. A notification keeps it running in the background.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
    }
}
