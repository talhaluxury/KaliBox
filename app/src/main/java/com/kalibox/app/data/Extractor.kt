package com.kalibox.app.data

import android.system.ErrnoException
import android.system.Os
import com.kalibox.app.util.AppLog
import com.kalibox.app.util.ArchivePaths
import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Tar / tar.xz extractor that preserves symlinks, hard links and permissions, and skips device nodes. */
object Extractor {
    private const val TAG = "Extractor"

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }

    /**
     * @param totalBytes size of the (compressed) input, used for the progress percentage
     * @param isActive polled once per entry so the caller can cancel
     */
    fun extract(
        input: InputStream,
        totalBytes: Long,
        xz: Boolean,
        dest: File,
        isActive: () -> Boolean,
        onProgress: (Int) -> Unit,
    ) {
        dest.mkdirs()
        val counting = Counting(BufferedInputStream(input, 256 * 1024))
        val source: InputStream = if (xz) XZCompressorInputStream(counting) else counting
        var strip: String? = null
        var first = true
        var errors = 0
        var skipped = 0
        var lastPct = -1
        TarArchiveInputStream(source).use { tin ->
            while (true) {
                val e: TarArchiveEntry = tin.nextTarEntry ?: break
                if (!isActive()) throw CancellationException("Extraction cancelled")
                val raw = ArchivePaths.clean(e.name) ?: continue
                if (first) {
                    first = false
                    strip = ArchivePaths.topPrefix(raw)
                }
                val rel = ArchivePaths.strip(raw, strip)
                if (rel.isEmpty()) continue
                val target = File(dest, rel)
                try {
                    when {
                        e.isDirectory -> {
                            target.mkdirs()
                            Os.chmod(target.path, (e.mode and 0x1FF) or 0x1C0)
                        }
                        e.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            target.delete()
                            Os.symlink(e.linkName, target.path)
                        }
                        e.isLink -> {
                            val linkRel = ArchivePaths.strip(ArchivePaths.clean(e.linkName) ?: "", strip)
                            val src = File(dest, linkRel)
                            target.parentFile?.mkdirs()
                            target.delete()
                            try {
                                Os.link(src.path, target.path)
                            } catch (_: ErrnoException) {
                                src.copyTo(target, overwrite = true)
                            }
                        }
                        e.isFile -> {
                            target.parentFile?.mkdirs()
                            target.delete()
                            FileOutputStream(target).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = tin.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                            Os.chmod(target.path, (e.mode and 0x1FF) or 0x180)
                        }
                        else -> skipped++ // char/block devices, fifos: cannot be created without root
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    errors++
                    AppLog.e(TAG, "Failed entry ${e.name}: ${ex.message}")
                    if (errors > 500) throw IOException("Too many extraction errors (last: ${ex.message})")
                }
                if (totalBytes > 0) {
                    val pct = (counting.count * 100 / totalBytes).toInt().coerceIn(0, 100)
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct)
                    }
                }
            }
        }
        AppLog.i(TAG, "Extraction finished: errors=$errors skippedDevices=$skipped")
    }
}
