package com.kalibox.app.util

import android.content.Context
import java.io.File

class AppPaths(ctx: Context) {
    val files: File = ctx.filesDir
    val rootfs = File(files, "kali")
    val rootfsTmp = File(files, "kali.tmp")
    val tmp = File(files, "tmp")
    val libDir = File(files, "lib")
    val fakeProc = File(files, "fakeproc")
    val download = File(files, "downloads/rootfs.tar.xz.part")
    val cache: File = ctx.cacheDir
    val nativeDir: String = ctx.applicationInfo.nativeLibraryDir

    fun rootfsInstalled(): Boolean = File(rootfs, "usr/bin/bash").exists()
    fun desktopInstalled(): Boolean = File(rootfs, "usr/bin/vncserver").exists() && File(rootfs, "usr/bin/startxfce4").exists()
}
