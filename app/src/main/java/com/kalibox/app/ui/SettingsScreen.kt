package com.kalibox.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import com.kalibox.app.MainViewModel
import com.kalibox.app.domain.Screen
import com.kalibox.app.domain.Tools
import com.kalibox.app.service.SessionHub
import com.kalibox.app.util.AppLog

@Composable
fun SettingsScreen(vm: MainViewModel) {
    BackHandler { vm.go(Screen.HOME) }
    val ctx = LocalContext.current
    val storage by vm.storageInfo.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var resolution by remember { mutableStateOf(vm.prefs.resolution) }
    var scheme by remember { mutableStateOf(vm.prefs.colorScheme) }
    var newPw by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>().apply { addAll(Tools.all.map { it.label }) } }

    LaunchedEffect(Unit) { vm.refreshStorage() }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-tar")) { uri -> uri?.let { vm.backup(it) } }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.restore(it) } }
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.go(Screen.HOME) }) { Text("← Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        Section("Storage")
        Text(storage, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.refreshStorage() }) { Text("Refresh") }
            OutlinedButton(onClick = { vm.clearCache() }) { Text("Clear cache") }
        }

        Section("Updates")
        Button(onClick = { vm.aptUpgrade() }, Modifier.fillMaxWidth()) { Text("Run apt update && apt upgrade") }

        Section("Tool bundles")
        Tools.all.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = t.label in picked, onCheckedChange = { c -> if (c) picked.add(t.label) else picked.remove(t.label) })
                Text(t.label)
            }
        }
        Button(onClick = { vm.installTools(Tools.all.filter { it.label in picked }.flatMap { it.packages }) }, Modifier.fillMaxWidth()) { Text("Install selected") }

        Section("Desktop")
        Text("Resolution (restarts a running desktop)")
        listOf("720p" to "720p (1280×720)", "1080p" to "1080p (1920×1080)", "match" to "Match screen").forEach { (k, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = resolution == k, onClick = { resolution = k; vm.setResolution(k) })
                Text(label)
            }
        }
        OutlinedTextField(
            value = newPw, onValueChange = { newPw = it.take(8) }, label = { Text("New VNC password (6–8 chars)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { vm.changeVncPassword(newPw); newPw = "" }) { Text("Change VNC password") }

        Section("Terminal")
        SessionHub.schemes.keys.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = scheme == name, onClick = { scheme = name; SessionHub.applyScheme(name) })
                Text(name)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { SessionHub.changeFont(-1) }) { Text("A−") }
            OutlinedButton(onClick = { SessionHub.changeFont(1) }) { Text("A+") }
            Text("or pinch-zoom in the terminal")
        }

        Section("Shared storage (/sdcard)")
        Text("Lets Kali read/write your phone storage at /sdcard.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= 30) {
                if (!Environment.isExternalStorageManager()) {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    } catch (_: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else vm.notice.value = "All-files access is already granted."
            } else {
                storagePerm.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
        }) { Text("Grant storage access") }

        Section("Root mode (WiFi monitor, injection, raw hardware)")
        Text(
            "On a rooted phone, KaliBox can run Kali with real root (su + chroot) instead of proot. This gives raw sockets, " +
                "network interfaces, USB devices and /sys access. Monitor mode and injection also need a WiFi chip/driver or kernel that supports them " +
                "(e.g. a NetHunter-style kernel or an OTG USB adapter with a kernel driver). Backup/reset are disabled while root mode is on.",
            style = MaterialTheme.typography.bodySmall,
        )
        val rootOn = vm.prefs.rootMode
        Button(onClick = { if (rootOn) vm.disableRootMode() else vm.enableRootMode() }) {
            Text(if (rootOn) "Disable root mode" else "Enable root mode")
        }

        Section("Backup & reset")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { backupLauncher.launch("kalibox-backup.tar") }) { Text("Backup") }
            OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) { Text("Restore") }
        }
        OutlinedButton(onClick = { confirmReset = true }) { Text("Reinstall / reset rootfs") }

        Section("Logs")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showLog = true }) { Text("View logs") }
            OutlinedButton(onClick = { AppLog.shareIntent(ctx)?.let { ctx.startActivity(it) } }) { Text("Share logs") }
        }

        Text("KaliBox is GPLv3. Authorized use only.", style = MaterialTheme.typography.bodySmall)
    }

    if (showLog) {
        val logText = remember { AppLog.tail() }
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Log (last lines)") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(logText, fontSize = 10.sp, lineHeight = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("KaliBox log", logText))
                    vm.notice.value = "Log copied. Paste it in the chat."
                    showLog = false
                }) { Text("Copy all") }
            },
            dismissButton = { TextButton(onClick = { showLog = false }) { Text("Close") } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset Kali?") },
            text = { Text("This deletes the whole Kali rootfs (all files and installed packages inside it) and starts setup again. Make a backup first if you need your data.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetRootfs() }) { Text("Delete & reinstall") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth())
}
