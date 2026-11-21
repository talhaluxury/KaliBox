package com.kalibox.app.util

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Tree helpers that NEVER follow symlinks (rootfs contains absolute symlinks). */
object FileUtil {
    fun deleteTree(root: File) {
        val p = root.toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                try { Files.delete(file) } catch (_: IOException) { }
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                try { Files.delete(dir) } catch (_: IOException) {
                    dir.toFile().setWritable(true, true)
                    try { Files.delete(dir) } catch (_: IOException) { }
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun size(root: File): Long {
        var total = 0L
        val p = root.toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return 0
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                total += attrs.size()
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return total
    }
}
