package com.kalibox.app.data

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object Backup {
    /** Writes the rootfs as a plain tar (symlinks + modes preserved). */
    fun write(root: File, out: OutputStream, onFiles: (Int) -> Unit) {
        val rootPath = root.toPath()
        var n = 0
        TarArchiveOutputStream(BufferedOutputStream(out, 256 * 1024)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                private fun mode(p: Path, default: Int): Int =
                    try { Os.lstat(p.toString()).st_mode and 0xFFF } catch (_: Exception) { default }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != rootPath) {
                        val e = TarArchiveEntry(rootPath.relativize(dir).toString() + "/")
                        e.mode = mode(dir, 493)
                        tar.putArchiveEntry(e)
                        tar.closeArchiveEntry()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val rel = rootPath.relativize(file).toString()
                    if (attrs.isSymbolicLink) {
                        val e = TarArchiveEntry(rel, TarConstants.LF_SYMLINK)
                        e.linkName = Files.readSymbolicLink(file).toString()
                        e.mode = mode(file, 511)
                        tar.putArchiveEntry(e)
                        tar.closeArchiveEntry()
                    } else if (attrs.isRegularFile) {
                        val e = TarArchiveEntry(rel)
                        e.size = attrs.size()
                        e.mode = mode(file, 420)
                        tar.putArchiveEntry(e)
                        Files.copy(file, tar)
                        tar.closeArchiveEntry()
                    }
                    n++
                    if (n % 500 == 0) onFiles(n)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
            tar.finish()
        }
        onFiles(n)
    }
}
