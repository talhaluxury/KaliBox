package com.kalibox.app.data

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import com.kalibox.app.util.AppLog
import com.kalibox.app.util.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream

class Proot(private val ctx: Context, val paths: AppPaths, private val prefs: Prefs) {
    val prootBin: String get() = File(paths.nativeDir, "libproot.so").path
    val rootMode: Boolean get() = prefs.rootMode
    private val rootScript get() = File(paths.files, "rootchroot.sh")
    private val unmountScript get() = File(paths.files, "rootunmount.sh")

    fun suPath(): String =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su").firstOrNull { File(it).exists() }
            ?: "/system/bin/su"

    /** Executable used to start a terminal session: su (root mode) or proot. */
    fun launcher(): String = if (rootMode) suPath() else prootBin

    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    private val fakeNames = listOf("stat", "loadavg", "uptime", "version", "vmstat")

    /** Creates the libtalloc.so.2 symlink, tmp dirs and fake /proc files. Cheap; call before every launch. */
    fun prepare() {
        paths.tmp.mkdirs()
        paths.libDir.mkdirs()
        // proot is linked against libtalloc.so.2 but Android only extracts files named lib*.so
        val link = File(paths.libDir, "libtalloc.so.2")
        try {
            link.delete()
            Os.symlink(File(paths.nativeDir, "libtalloc.so").path, link.path)
        } catch (e: ErrnoException) {
            AppLog.e("Proot", "talloc symlink failed", e)
        }
        for ((asset, dest) in listOf("scripts/rootchroot.sh" to rootScript, "scripts/rootunmount.sh" to unmountScript)) {
            try { ctx.assets.open(asset).use { i -> dest.outputStream().use { o -> i.copyTo(o) } } } catch (e: Exception) { AppLog.e("Proot", "asset copy $asset", e) }
        }
        paths.fakeProc.mkdirs()
        val cores = Runtime.getRuntime().availableProcessors()
        val up = SystemClock.elapsedRealtime() / 1000
        File(paths.fakeProc, "stat").writeText(buildString {
            append("cpu  1000 0 1000 100000 0 0 0 0 0 0\n")
            for (i in 0 until cores) append("cpu$i 250 0 250 25000 0 0 0 0 0 0\n")
            append("intr 0\nctxt 0\nbtime ${System.currentTimeMillis() / 1000 - up}\nprocesses 1000\nprocs_running 1\nprocs_blocked 0\n")
        })
        File(paths.fakeProc, "loadavg").writeText("0.12 0.07 0.02 2/165 765\n")
        File(paths.fakeProc, "uptime").writeText("$up.00 ${up * cores}.00\n")
        File(paths.fakeProc, "version").writeText("Linux version 5.4.0-kalibox (proot@kali) (gcc version 12.2.0) #1 SMP PREEMPT\n")
        File(paths.fakeProc, "vmstat").writeText("nr_free_pages 100000\n")
    }

    private fun readable(path: String): Boolean = try {
        FileInputStream(path).use { it.read() }
        true
    } catch (_: Exception) {
        false
    }

    fun hostEnv(): Map<String, String> {
        val env = linkedMapOf(
            "PATH" to "/system/bin:/system/xbin:/sbin:/su/bin",
            "PROOT_LOADER" to File(paths.nativeDir, "libproot-loader.so").path,
            "PROOT_TMP_DIR" to paths.tmp.path,
            "TMPDIR" to paths.tmp.path,
            "LD_LIBRARY_PATH" to "${paths.libDir.path}:${paths.nativeDir}",
        )
        if (prefs.noSeccomp) env["PROOT_NO_SECCOMP"] = "1"
        return env
    }

    fun hostEnvArray(): Array<String> = hostEnv().map { "${it.key}=${it.value}" }.toTypedArray()

    private fun guestEnv(extra: Map<String, String>): List<String> {
        val base = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            "SHELL" to "/bin/bash",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
        base.putAll(extra)
        return base.map { "${it.key}=${it.value}" }
    }

    /** Full argv (argv[0] = proot) for running [guestCmd] inside the rootfs. */
    fun args(guestCmd: List<String>, extraEnv: Map<String, String> = emptyMap()): List<String> {
        if (rootMode) {
            val cmd = buildString {
                append("sh ").append(q(rootScript.path)).append(' ').append(q(paths.rootfs.path))
                guestEnv(extraEnv).forEach { append(' ').append(q(it)) }
                guestCmd.forEach { append(' ').append(q(it)) }
            }
            return listOf(suPath(), "-c", cmd)
        }
        val a = mutableListOf(
            prootBin, "-0", "--link2symlink", "--kill-on-exit",
            "-r", paths.rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "${paths.tmp.path}:/dev/shm",
        )
        for (n in fakeNames) {
            if (!readable("/proc/$n")) a += listOf("-b", "${File(paths.fakeProc, n).path}:/proc/$n")
        }
        val shared = Environment.getExternalStorageDirectory()
        if (shared != null && shared.exists()) a += listOf("-b", "${shared.path}:/sdcard")
        a += listOf("-w", "/root", "/usr/bin/env", "-i")
        a += guestEnv(extraEnv)
        a += guestCmd
        return a
    }

    fun sessionArgs(): List<String> = args(listOf("/bin/bash", "-l"))

    fun buildProcess(guestCmd: List<String>, extraEnv: Map<String, String> = emptyMap()): ProcessBuilder {
        prepare()
        return ProcessBuilder(args(guestCmd, extraEnv)).apply {
            directory(paths.files)
            environment().clear()
            environment().putAll(hostEnv())
            redirectErrorStream(true)
        }
    }

    private suspend fun stream(p: Process, onLine: (String) -> Unit): Int = coroutineScope {
        val watcher = launch {
            try { awaitCancellation() } finally { p.destroyForcibly() }
        }
        val reader = p.inputStream.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            onLine(line)
        }
        val code = p.waitFor()
        watcher.cancel()
        code
    }

    /** Runs a script in the guest (proot or root chroot), streaming merged output. Returns the exit code. */
    suspend fun exec(script: String, env: Map<String, String> = emptyMap(), onLine: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val p = buildProcess(listOf("/bin/bash", "-c", script), env).start()
            p.outputStream.close() // no interactive stdin: apt must never wait for input
            stream(p, onLine)
        }

    /** Runs a command on the Android side as root (su -c). */
    suspend fun hostExec(cmd: String, onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(listOf(suPath(), "-c", cmd)).redirectErrorStream(true)
        pb.directory(paths.files)
        val p = pb.start()
        p.outputStream.close()
        stream(p, onLine)
    }

    suspend fun hasRoot(): Boolean {
        var uid = ""
        val code = try { hostExec("id -u") { uid += it.trim() } } catch (e: Exception) { AppLog.e("Proot", "su failed", e); -1 }
        return code == 0 && uid == "0"
    }

    /** Unmounts every bind mount below the rootfs. Must run before deleting/backing up in root mode. */
    suspend fun unmountAll() {
        prepare()
        hostExec("sh " + q(unmountScript.path) + " " + q(paths.rootfs.path)) { }
    }

    suspend fun rootKill() {
        try { hostExec("pkill -f Xtigervnc; pkill -f websockify; pkill -f start-desktop.sh") { } } catch (_: Exception) { }
    }

    /** Verifies proot actually starts; falls back to PROOT_NO_SECCOMP=1 automatically. */
    suspend fun probe(): Boolean {
        suspend fun tryOnce(): Boolean = withTimeoutOrNull(30_000) {
            try { exec("/bin/true") { AppLog.i("Proot", "probe: $it") } == 0 } catch (e: Exception) {
                AppLog.e("Proot", "probe failed", e); false
            }
        } ?: false
        if (tryOnce()) return true
        if (!prefs.noSeccomp) {
            AppLog.i("Proot", "Retrying with PROOT_NO_SECCOMP=1")
            prefs.noSeccomp = true
            if (tryOnce()) return true
            prefs.noSeccomp = false
        }
        return false
    }
}
