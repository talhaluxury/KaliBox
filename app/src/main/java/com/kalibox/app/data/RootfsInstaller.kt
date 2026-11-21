package com.kalibox.app.data

import android.content.Context
import com.kalibox.app.domain.InstallException
import com.kalibox.app.domain.InstallProgress
import com.kalibox.app.domain.Stage
import com.kalibox.app.util.AppLog
import com.kalibox.app.util.AppPaths
import com.kalibox.app.util.Constants
import com.kalibox.app.util.FileUtil
import com.kalibox.app.util.Fmt
import com.kalibox.app.util.SystemChecks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.FileInputStream

class RootfsInstaller(private val ctx: Context, private val paths: AppPaths) {
    private val downloader = Downloader()

    suspend fun install(report: (InstallProgress) -> Unit) {
        var p = InstallProgress(Stage.CHECK, -1, "Checking requirements…", running = true)
        report(p)

        // 1. pre-flight
        if (!SystemChecks.hasNetwork(ctx)) throw InstallException("No network connection. Connect to Wi-Fi or mobile data and try again.")
        val free = SystemChecks.freeBytes(paths.files)
        val partial = if (paths.download.exists()) paths.download.length() else 0L
        if (free + partial < Constants.MIN_FREE_ROOTFS_BYTES) {
            throw InstallException(
                "Not enough storage: ${Fmt.bytes(free)} free, ${Fmt.bytes(Constants.MIN_FREE_ROOTFS_BYTES)} required. " +
                    "Free some space and retry.",
            )
        }

        // 2. checksum source
        val fileName = Constants.ROOTFS_URL.substringAfterLast('/')
        val expected: String? = when {
            Constants.ROOTFS_SHA256.isNotBlank() -> Constants.ROOTFS_SHA256.trim().lowercase()
            else -> try {
                downloader.fetchText(Constants.ROOTFS_SHA256_URL).lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.endsWith(fileName) }
                    ?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
            } catch (e: Exception) {
                AppLog.e("Installer", "Could not fetch checksum list", e)
                null
            }
        }

        // 3. download (resumable)
        p = p.copy(stage = Stage.DOWNLOAD, message = "Downloading Kali rootfs…")
        report(p)
        downloader.download(Constants.ROOTFS_URL, paths.download) { done, total, speed ->
            val pct = if (total > 0) (done * 100 / total).toInt() else -1
            report(p.copy(percent = pct, bytes = done, total = total, speed = speed))
        }

        // 4. verify
        p = InstallProgress(Stage.VERIFY, -1, "Verifying SHA256…", running = true)
        report(p)
        val size = paths.download.length()
        val actual = withContext(Dispatchers.IO) {
            Fmt.sha256(paths.download) { read -> report(p.copy(percent = (read * 100 / size).toInt(), bytes = read, total = size)) }
        }
        if (expected != null && actual != expected) {
            paths.download.delete()
            throw InstallException("Checksum mismatch (the download was corrupted). The partial file was removed — tap Install to download again.")
        }
        if (expected == null) AppLog.i("Installer", "WARNING: rootfs installed WITHOUT checksum verification. sha256=$actual")

        // 5. extract into a temp dir, then swap
        p = InstallProgress(Stage.EXTRACT, 0, if (expected == null) "Extracting… (checksum list unavailable: unverified)" else "Extracting…", running = true)
        report(p)
        withContext(Dispatchers.IO) {
            FileUtil.deleteTree(paths.rootfsTmp)
            Extractor.extract(
                FileInputStream(paths.download), size, xz = true, dest = paths.rootfsTmp,
                isActive = { this.isActive },
            ) { pct -> report(p.copy(percent = pct)) }

            report(InstallProgress(Stage.CONFIGURE, -1, "Applying first-run fixes…", running = true))
            RootfsConfigurator.apply(paths.rootfsTmp)

            FileUtil.deleteTree(paths.rootfs)
            if (!paths.rootfsTmp.renameTo(paths.rootfs)) throw InstallException("Could not move extracted rootfs into place.")
            paths.download.delete()
        }
        if (!paths.rootfsInstalled()) throw InstallException("Extraction finished but /usr/bin/bash is missing. The archive may be incompatible; see logs.")
        report(InstallProgress(Stage.DONE, 100, "Kali is installed.", running = false))
    }
}
