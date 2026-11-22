package com.kalibox.app.service

import android.content.Context
import com.kalibox.app.data.DesktopSetup
import com.kalibox.app.data.Proot
import com.kalibox.app.util.AppLog
import com.kalibox.app.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

enum class DesktopState { STOPPED, STARTING, RUNNING, ERROR }

/** Runs one long-lived proot process (VNC + websockify supervisor) and restarts it if it dies. */
object DesktopController {
    private const val TAG = "Desktop"
    val state = MutableStateFlow(DesktopState.STOPPED)
    val error = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    @Volatile private var lastProot: Proot? = null

    fun start(app: Context, proot: Proot, geometry: String) {
        if (state.value == DesktopState.STARTING || state.value == DesktopState.RUNNING) return
        stopping = false
        lastProot = proot
        error.value = null
        state.value = DesktopState.STARTING
        KaliService.start(app)
        job = scope.launch { supervise(app, proot, geometry) }
    }

    private suspend fun supervise(app: Context, proot: Proot, geometry: String) {
        var restarts = 0
        while (!stopping) {
            try {
                DesktopSetup.writeScripts(app, proot.paths.rootfs)
                val p = proot.buildProcess(
                    listOf("/bin/bash", "/${Constants.GUEST_DIR}/start-desktop.sh"),
                    mapOf("GEOMETRY" to geometry),
                ).start()
                process = p
                p.outputStream.close()
                val reader = scope.launch {
                    try { p.inputStream.bufferedReader().forEachLine { AppLog.i(TAG, it) } } catch (_: Exception) { }
                }
                var ready = false
                var waited = 0
                while (!ready && p.isAlive && waited < 150 && !stopping) {
                    if (portOpen(Constants.NOVNC_PORT) && portOpen(Constants.VNC_PORT)) {
                        ready = true
                        state.value = DesktopState.RUNNING
                    } else {
                        delay(1000)
                        waited++
                    }
                }
                if (!ready && p.isAlive && !stopping) AppLog.e(TAG, "VNC server (port ${Constants.VNC_PORT}) / noVNC not reachable after ${waited}s")
                p.waitFor()
                reader.cancel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "desktop supervisor error", e)
                error.value = e.message
            }
            if (stopping) break
            restarts++
            if (restarts > 5) {
                error.value = error.value ?: "Desktop keeps crashing. Share the logs from Settings."
                state.value = DesktopState.ERROR
                KaliService.refresh(app)
                return
            }
            state.value = DesktopState.STARTING
            delay(2000)
        }
        state.value = DesktopState.STOPPED
        KaliService.refresh(app)
    }

    fun stop() {
        stopping = true
        process?.destroyForcibly()
        job?.cancel()
        process = null
        state.value = DesktopState.STOPPED
        lastProot?.let { pr -> if (pr.rootMode) scope.launch { pr.rootKill() } }
    }

    private fun portOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500); true }
    } catch (_: Exception) {
        false
    }
}
