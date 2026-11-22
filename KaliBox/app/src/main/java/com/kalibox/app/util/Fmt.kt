package com.kalibox.app.util

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

object Fmt {
    fun bytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = -1
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun speed(bps: Long): String = bytes(bps) + "/s"

    fun sha256(file: File, onProgress: ((Long) -> Unit)? = null): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
                onProgress?.invoke(total)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

object ArchivePaths {
    /** Normalizes an archive path. Returns null if it is unsafe (contains ".."). */
    fun clean(raw: String): String? {
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** Detects a wrapper directory such as "kali-arm64". */
    fun topPrefix(rel: String): String? {
        val seg = rel.substringBefore('/')
        return if (Regex("kali-[a-z0-9]+").matches(seg)) seg else null
    }

    fun strip(rel: String, top: String?): String {
        if (top == null) return rel
        if (rel == top) return ""
        return if (rel.startsWith("$top/")) rel.removePrefix("$top/") else rel
    }
}
