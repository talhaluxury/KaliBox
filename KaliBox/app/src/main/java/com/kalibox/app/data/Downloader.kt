package com.kalibox.app.data

import com.kalibox.app.domain.InstallException
import com.kalibox.app.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Resumable (HTTP Range) downloader with retry. Pause = cancel the coroutine; resume = call again. */
class Downloader {
    suspend fun download(url: String, dest: File, onProgress: (done: Long, total: Long, speed: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            var attempt = 0
            while (true) {
                try {
                    once(url, dest, onProgress)
                    return@withContext
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    attempt++
                    AppLog.e("Downloader", "attempt $attempt failed", e)
                    if (attempt >= 5) throw InstallException("Download failed after $attempt attempts: ${e.message}")
                    delay(2000L * attempt)
                }
            }
        }

    private suspend fun once(url: String, dest: File, cb: (Long, Long, Long) -> Unit) {
        val have = if (dest.exists()) dest.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "KaliBox/1.0")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code == 416) { cb(have, have, 0); return }   // already complete
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val offset = if (code == 206) have else 0L        // server ignored Range -> restart
            val total = if (code == 206) {
                c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: (have + c.contentLengthLong)
            } else c.contentLengthLong
            var done = offset
            val buf = ByteArray(64 * 1024)
            var lastT = System.nanoTime()
            var lastB = done
            FileOutputStream(dest, offset > 0).use { out ->
                c.inputStream.use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastT >= 500_000_000L) {
                            val speed = (done - lastB) * 1_000_000_000L / (now - lastT)
                            lastT = now
                            lastB = done
                            cb(done, total, speed)
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("Connection closed early ($done/$total)")
            cb(done, total, 0)
        } finally {
            c.disconnect()
        }
    }

    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
