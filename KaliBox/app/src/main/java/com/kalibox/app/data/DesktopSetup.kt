package com.kalibox.app.data

import android.content.Context
import android.system.Os
import com.kalibox.app.util.Constants
import java.io.File

object DesktopSetup {
    private val scripts = listOf("install-desktop.sh", "start-desktop.sh", "set-vnc-password.sh", "xstartup", "fake-bwrap")

    /** Copies helper scripts from assets into /root/.kalibox inside the rootfs. */
    fun writeScripts(ctx: Context, rootfs: File) {
        val dir = File(rootfs, Constants.GUEST_DIR).apply { mkdirs() }
        for (name in scripts) {
            val f = File(dir, name)
            f.delete()
            ctx.assets.open("scripts/$name").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            Os.chmod(f.path, 493)
        }
    }

    fun readAsset(ctx: Context, path: String): String = ctx.assets.open(path).bufferedReader().use { it.readText() }
}
