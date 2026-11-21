package com.kalibox.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kalibox.app.data.Backup
import com.kalibox.app.data.DesktopSetup
import com.kalibox.app.data.Extractor
import com.kalibox.app.data.Prefs
import com.kalibox.app.data.Proot
import com.kalibox.app.data.RootfsInstaller
import com.kalibox.app.domain.InstallException
import com.kalibox.app.domain.InstallProgress
import com.kalibox.app.domain.Screen
import com.kalibox.app.domain.Stage
import com.kalibox.app.domain.TaskUi
import com.kalibox.app.service.DesktopController
import com.kalibox.app.service.DesktopState
import com.kalibox.app.service.KaliService
import com.kalibox.app.service.SessionHub
import com.kalibox.app.util.AppLog
import com.kalibox.app.util.AppPaths
import com.kalibox.app.util.Constants
import com.kalibox.app.util.FileUtil
import com.kalibox.app.util.Fmt
import com.kalibox.app.util.SystemChecks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    val paths = AppPaths(app)
    val prefs = Prefs(app)
    val proot = Proot(app, paths, prefs)
    private val installer = RootfsInstaller(app, paths)

    val screen = MutableStateFlow(initialScreen())
    val install = MutableStateFlow(InstallProgress())
    val task = MutableStateFlow(TaskUi())
    val lastCrash = MutableStateFlow(AppLog.consumeCrash())
    val storageInfo = MutableStateFlow("Calculating…")
    val notice = MutableStateFlow<String?>(null)
    var taskReturn: Screen = Screen.HOME
        private set

    private var installJob: Job? = null

    private fun initialScreen(): Screen = when {
        !prefs.legalAccepted -> Screen.LEGAL
        !paths.rootfsInstalled() -> Screen.SETUP
        else -> Screen.HOME
    }

    fun go(s: Screen) { screen.value = s }

    fun acceptLegal() {
        prefs.legalAccepted = true
        screen.value = if (paths.rootfsInstalled()) Screen.HOME else Screen.SETUP
    }

    // ------------------------------------------------------------ rootfs install
    fun startInstall() {
        if (installJob?.isActive == true) return
        install.value = InstallProgress(Stage.CHECK, -1, "Starting…", running = true)
        KaliService.taskStarted(app)
        installJob = viewModelScope.launch {
            try {
                installer.install { install.value = it }
                prefs.desktopInstalled = paths.desktopInstalled()
                val ok = proot.probe()
                if (!ok) {
                    AppLog.e("Install", "proot probe failed after install")
                    install.value = InstallProgress(
                        Stage.DONE, 100,
                        "Kali installed, but proot could not start:\n" + proot.lastProbeOutput,
                        running = false,
                    )
                }
                screen.value = Screen.HOME
            } catch (e: CancellationException) {
                install.update { it.copy(paused = true, running = false, message = "Paused — tap Resume to continue") }
            } catch (e: InstallException) {
                AppLog.e("Install", e.message ?: "install error", e)
                install.update { it.copy(error = e.message, running = false) }
            } catch (e: Exception) {
                AppLog.e("Install", "unexpected", e)
                install.update { it.copy(error = "Unexpected error: ${e.message ?: e.javaClass.simpleName}", running = false) }
            } finally {
                KaliService.taskFinished(app)
            }
        }
    }

    fun pauseInstall() { installJob?.cancel() }

    // ------------------------------------------------------------ generic task runner
    private fun launchTask(
        title: String,
        back: Screen,
        block: suspend (log: (String) -> Unit) -> Int,
        onDone: (Int) -> Unit = {},
    ) {
        if (task.value.running) return
        taskReturn = back
        task.value = TaskUi(title, listOf("▶ $title"), running = true)
        screen.value = Screen.TASK
        KaliService.taskStarted(app)
        viewModelScope.launch {
            var code = -1
            try {
                code = block { line -> task.update { it.copy(lines = (it.lines + line).takeLast(600)) } }
            } catch (e: CancellationException) {
                code = -2
            } catch (e: Exception) {
                AppLog.e("Task", "$title failed", e)
                task.update { it.copy(lines = it.lines + "ERROR: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                task.update { it.copy(running = false, exitCode = code) }
                KaliService.taskFinished(app)
            }
            onDone(code)
        }
    }

    private fun prootTask(
        title: String,
        back: Screen,
        script: String,
        env: Map<String, String> = emptyMap(),
        pre: (() -> Unit)? = null,
        onDone: (Int) -> Unit = {},
    ) = launchTask(title, back, { log ->
        withContext(Dispatchers.IO) { pre?.invoke() }
        if (!paths.rootfsInstalled()) {
            log("Kali rootfs is not installed.")
            127
        } else if (!proot.probe()) {
            log("ERROR: proot failed to start.")
            proot.lastProbeOutput.lines().forEach { log(it) }
            127
        } else {
            proot.exec(script, env, log)
        }
    }, onDone)

    // ------------------------------------------------------------ terminal
    fun openTerminal() {
        if (SessionHub.sessions.value.isEmpty()) newSession()
        screen.value = Screen.TERMINAL
    }

    fun newSession() {
        if (!paths.rootfsInstalled()) { notice.value = "Install Kali first."; return }
        viewModelScope.launch {
            if (proot.probe()) SessionHub.newSession()
            else notice.value = "proot failed to start.\n\n" + proot.lastProbeOutput
        }
    }

    // ------------------------------------------------------------ desktop
    fun desktopWarnings(): List<String> {
        val w = mutableListOf<String>()
        val ram = SystemChecks.totalRam(app)
        if (ram < Constants.MIN_RAM_DESKTOP_BYTES) w += "Low RAM: ${Fmt.bytes(ram)} (4 GB or more recommended). The desktop may be slow or get killed."
        val free = SystemChecks.freeBytes(paths.files)
        if (free < Constants.MIN_FREE_DESKTOP_BYTES) w += "Low storage: ${Fmt.bytes(free)} free (4 GB or more needed for the desktop packages)."
        return w
    }

    fun onStartDesktop() {
        if (!paths.rootfsInstalled()) { notice.value = "Install Kali first."; return }
        if (!prefs.desktopInstalled && !paths.desktopInstalled()) { screen.value = Screen.DESKTOP_SETUP; return }
        prefs.desktopInstalled = true
        if (prefs.vncPassword.isNullOrEmpty()) { screen.value = Screen.DESKTOP_SETUP; return }
        launchDesktop()
    }

    private fun launchDesktop() {
        viewModelScope.launch {
            if (!proot.probe()) { notice.value = "proot failed to start.\n\n" + proot.lastProbeOutput; return@launch }
            DesktopController.start(app, proot, geometry())
            screen.value = Screen.DESKTOP
        }
    }

    fun installDesktop(password: String) {
        if (password.length !in 6..8) { notice.value = "VNC password must be 6–8 characters."; return }
        if (!prefs.secureStorageOk) { notice.value = "Secure storage is unavailable on this device."; return }
        prefs.vncPassword = password
        prootTask(
            "Install Kali Desktop (XFCE)", Screen.HOME,
            "bash /${Constants.GUEST_DIR}/install-desktop.sh",
            mapOf("VNCPW" to password),
            pre = { DesktopSetup.writeScripts(app, paths.rootfs) },
        ) { code ->
            if (code == 0) prefs.desktopInstalled = true
        }
    }

    fun changeVncPassword(password: String) {
        if (password.length !in 6..8) { notice.value = "VNC password must be 6–8 characters."; return }
        prefs.vncPassword = password
        prootTask(
            "Set VNC password", Screen.SETTINGS,
            "bash /${Constants.GUEST_DIR}/set-vnc-password.sh", mapOf("VNCPW" to password),
            pre = { DesktopSetup.writeScripts(app, paths.rootfs) },
        )
    }

    fun geometry(): String = when (prefs.resolution) {
        "1080p" -> "1920x1080"
        "match" -> {
            val dm = app.resources.displayMetrics
            var w = dm.widthPixels
            var h = dm.heightPixels
            val long = max(w, h)
            if (long > 1920) { w = w * 1920 / long; h = h * 1920 / long }
            w = max(w, 640); h = max(h, 360)
            "${w and 1.inv()}x${h and 1.inv()}"
        }
        else -> "1280x720"
    }

    fun setResolution(mode: String) {
        prefs.resolution = mode
        if (DesktopController.state.value != DesktopState.STOPPED) {
            DesktopController.stop()
            launchDesktop()
        }
    }

    fun stopDesktop() {
        DesktopController.stop()
        screen.value = Screen.HOME
    }

    fun stopAll() {
        DesktopController.stop()
        SessionHub.closeAll()
        KaliService.refresh(app)
        screen.value = Screen.HOME
    }

    // ------------------------------------------------------------ tools / apt
    fun installTools(packages: List<String>) {
        if (packages.isEmpty()) return
        val pk = packages.joinToString(" ")
        prootTask(
            "Install tools", Screen.SETTINGS,
            "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y $pk",
        )
    }

    fun aptUpgrade() = prootTask(
        "apt update && apt upgrade", Screen.SETTINGS,
        "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get -y upgrade",
    )

    // ------------------------------------------------------------ maintenance
    fun refreshStorage() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                val root = FileUtil.size(paths.rootfs)
                val cache = FileUtil.size(paths.cache) + FileUtil.size(paths.tmp) +
                    (if (paths.download.exists()) paths.download.length() else 0L)
                "Kali rootfs: ${Fmt.bytes(root)}\nCache/temp: ${Fmt.bytes(cache)}\nFree on device: ${Fmt.bytes(SystemChecks.freeBytes(paths.files))}"
            }
            storageInfo.value = text
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                FileUtil.deleteTree(paths.cache)
                FileUtil.deleteTree(paths.tmp)
                paths.tmp.mkdirs()
                paths.download.delete()
            }
            notice.value = "Cache cleared."
            refreshStorage()
        }
    }

    private fun rootBlocked(): Boolean {
        if (!prefs.rootMode) return false
        notice.value = "Turn off Root mode first (Settings → Root mode), then try again."
        return true
    }

    fun enableRootMode() = launchTask("Enable root mode", Screen.SETTINGS, { log ->
        DesktopController.stop()
        SessionHub.closeAll()
        log("Requesting root… accept the su/Magisk prompt.")
        if (!proot.hasRoot()) {
            log("Root is not available on this device (su failed or was denied).")
            1
        } else {
            log("Root OK. Giving the rootfs to root (can take a few minutes)…")
            val c = proot.hostExec("chown -hR 0:0 " + proot.q(paths.rootfs.path)) { log(it) }
            if (c == 0) { prefs.rootMode = true; log("Root mode ENABLED. New terminals/desktops now run with real root.") }
            else log("chown failed (exit $c). Root mode NOT enabled.")
            c
        }
    })

    fun disableRootMode() = launchTask("Disable root mode", Screen.SETTINGS, { log ->
        DesktopController.stop()
        SessionHub.closeAll()
        log("Unmounting…")
        proot.unmountAll()
        log("Giving the rootfs back to the app (can take a few minutes)…")
        val uid = android.os.Process.myUid()
        val c = proot.hostExec("chown -hR $uid:$uid " + proot.q(paths.rootfs.path)) { log(it) }
        if (c == 0) { prefs.rootMode = false; log("Root mode disabled (proot mode).") } else log("chown failed (exit $c).")
        c
    })

    fun resetRootfs() {
        if (rootBlocked()) return
        stopAll()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                FileUtil.deleteTree(paths.rootfs)
                FileUtil.deleteTree(paths.rootfsTmp)
                paths.download.delete()
            }
            prefs.desktopInstalled = false
            install.value = InstallProgress()
            screen.value = Screen.SETUP
        }
    }

    fun backup(uri: Uri) { if (rootBlocked()) return; backupNow(uri) }

    private fun backupNow(uri: Uri) = launchTask("Backup rootfs", Screen.SETTINGS, { log ->
        DesktopController.stop()
        SessionHub.closeAll()
        withContext(Dispatchers.IO) {
            val out = app.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Cannot open destination")
            out.use { Backup.write(paths.rootfs, it) { n -> log("$n files written…") } }
        }
        log("Backup complete.")
        0
    })

    fun restore(uri: Uri) { if (rootBlocked()) return; restoreNow(uri) }

    private fun restoreNow(uri: Uri) = launchTask("Restore rootfs", Screen.SETTINGS, { log ->
        DesktopController.stop()
        SessionHub.closeAll()
        withContext(Dispatchers.IO) {
            var size = 0L
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst()) size = c.getLong(i)
            }
            log("Removing current rootfs…")
            FileUtil.deleteTree(paths.rootfs)
            val input = app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open backup file")
            var last = -10
            input.use {
                Extractor.extract(it, size, xz = false, dest = paths.rootfs, isActive = { true }) { pct ->
                    if (pct >= last + 10) { last = pct; log("Extracting… $pct%") }
                }
            }
        }
        prefs.desktopInstalled = paths.desktopInstalled()
        log(if (paths.rootfsInstalled()) "Restore complete." else "Restore finished but /usr/bin/bash is missing — not a KaliBox backup?")
        if (paths.rootfsInstalled()) 0 else 1
    })

    fun consumeNotice() { notice.value = null }
    fun dismissCrash() { lastCrash.value = null }
}
