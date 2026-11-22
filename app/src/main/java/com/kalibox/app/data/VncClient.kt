package com.kalibox.app.data

import android.graphics.Bitmap
import com.kalibox.app.util.AppLog
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal native RFB (VNC) client: VNC authentication, Raw + CopyRect + DesktopSize encodings.
 * It only talks to the localhost TigerVNC server, so Raw is fast enough and nothing needs a WebView.
 */
class VncClient(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        fun onUpdate()
        fun onFbSize(w: Int, h: Int)
    }

    val lock = Any()
    @Volatile var bitmap: Bitmap? = null
    @Volatile var fbW = 0
    @Volatile var fbH = 0
    @Volatile var connected = false
    private var fb = IntArray(0)

    @Volatile private var running = true
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "vnc-send").also { it.isDaemon = true } }

    fun start() {
        Thread({ loop() }, "vnc-client").also { it.isDaemon = true }.start()
    }

    fun close() {
        running = false
        try { socket?.close() } catch (_: Exception) { }
        sender.shutdownNow()
    }

    private fun loop() {
        while (running) {
            try {
                session()
            } catch (e: Exception) {
                if (running) {
                    AppLog.e("vnc", "session ended: ${e.message}")
                    listener.onStatus("Disconnected (${e.message}). Reconnecting…")
                }
            }
            connected = false
            try { socket?.close() } catch (_: Exception) { }
            var waited = 0
            while (running && waited < 15) {
                try { Thread.sleep(100) } catch (_: InterruptedException) { }
                waited++
            }
        }
    }

    private fun session() {
        listener.onStatus("Connecting…")
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 5000)
        s.tcpNoDelay = true
        s.soTimeout = 15000 // handshake must answer, otherwise show an error instead of hanging forever
        socket = s
        listener.onStatus("Connected to port $port, waiting for the VNC server…")
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val output = s.getOutputStream()
        out = output

        // --- handshake
        try { input.readFully(ByteArray(12)) } catch (e: java.net.SocketTimeoutException) {
            throw IOException("VNC server accepted the connection but sent no greeting")
        }
        listener.onStatus("Logging in…")
        output.write("RFB 003.008\n".toByteArray()); output.flush()
        val n = input.readUnsignedByte()
        if (n == 0) {
            val len = input.readInt().coerceIn(0, 4096)
            val msg = ByteArray(len); input.readFully(msg)
            throw IOException(String(msg))
        }
        val types = ByteArray(n); input.readFully(types)
        if (!types.contains(2.toByte())) throw IOException("VNC password login not offered by the server")
        output.write(byteArrayOf(2)); output.flush()
        val challenge = ByteArray(16); input.readFully(challenge)
        output.write(VncAuth.response(password, challenge)); output.flush()
        if (input.readInt() != 0) throw IOException("wrong VNC password")
        output.write(byteArrayOf(1)); output.flush() // shared
        listener.onStatus("Loading screen…")

        val w = input.readUnsignedShort()
        val h = input.readUnsignedShort()
        input.readFully(ByteArray(16)) // server pixel format (we set our own below)
        val nameLen = input.readInt().coerceIn(0, 65536)
        input.readFully(ByteArray(nameLen))
        resize(w, h)

        // SetPixelFormat: 32 bpp, depth 24, little endian, true colour, R<<16 G<<8 B
        output.write(
            byteArrayOf(
                0, 0, 0, 0,
                32, 24, 0, 1,
                0, -1, 0, -1, 0, -1, // red/green/blue max = 255
                16, 8, 0, 0, 0, 0,
            ),
        )
        // SetEncodings: CopyRect, Raw, DesktopSize
        output.write(byteArrayOf(2, 0, 0, 3))
        output.write(int32(1)); output.write(int32(0)); output.write(int32(-223))
        output.flush()
        request(false)
        s.soTimeout = 0 // the server only talks when the screen changes
        connected = true
        listener.onStatus("")

        // --- main loop
        while (running) {
            when (val type = input.readUnsignedByte()) {
                0 -> {
                    input.readUnsignedByte()
                    val rects = input.readUnsignedShort()
                    for (i in 0 until rects) {
                        val x = input.readUnsignedShort()
                        val y = input.readUnsignedShort()
                        val rw = input.readUnsignedShort()
                        val rh = input.readUnsignedShort()
                        when (val enc = input.readInt()) {
                            0 -> readRaw(input, x, y, rw, rh)
                            1 -> copyRect(input, x, y, rw, rh)
                            -223 -> resize(rw, rh)
                            else -> throw IOException("unsupported encoding $enc")
                        }
                    }
                    listener.onUpdate()
                    request(true)
                }
                1 -> throw IOException("colour maps are not supported")
                2 -> { /* bell */ }
                3 -> {
                    input.readFully(ByteArray(3))
                    val len = input.readInt().coerceIn(0, 1 shl 20)
                    input.readFully(ByteArray(len))
                }
                else -> throw IOException("unknown server message $type")
            }
        }
    }

    private fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || w * h > 40_000_000) throw IOException("bad framebuffer size ${w}x$h")
        synchronized(lock) {
            fbW = w
            fbH = h
            fb = IntArray(w * h) { -16777216 }
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        listener.onFbSize(w, h)
    }

    private fun readRaw(input: DataInputStream, x: Int, y: Int, rw: Int, rh: Int) {
        val buf = ByteArray(rw * rh * 4)
        input.readFully(buf)
        synchronized(lock) {
            if (x + rw > fbW || y + rh > fbH) return
            var p = 0
            for (row in 0 until rh) {
                var idx = (y + row) * fbW + x
                for (col in 0 until rw) {
                    fb[idx++] = -16777216 or
                        ((buf[p + 2].toInt() and 0xFF) shl 16) or
                        ((buf[p + 1].toInt() and 0xFF) shl 8) or
                        (buf[p].toInt() and 0xFF)
                    p += 4
                }
            }
            bitmap?.setPixels(fb, y * fbW + x, fbW, x, y, rw, rh)
        }
    }

    private fun copyRect(input: DataInputStream, x: Int, y: Int, rw: Int, rh: Int) {
        val sx = input.readUnsignedShort()
        val sy = input.readUnsignedShort()
        synchronized(lock) {
            if (x + rw > fbW || y + rh > fbH || sx + rw > fbW || sy + rh > fbH) return
            if (sy < y) {
                for (row in rh - 1 downTo 0) System.arraycopy(fb, (sy + row) * fbW + sx, fb, (y + row) * fbW + x, rw)
            } else {
                for (row in 0 until rh) System.arraycopy(fb, (sy + row) * fbW + sx, fb, (y + row) * fbW + x, rw)
            }
            bitmap?.setPixels(fb, y * fbW + x, fbW, x, y, rw, rh)
        }
    }

    private fun request(incremental: Boolean) {
        val w = fbW
        val h = fbH
        send(
            byteArrayOf(
                3, if (incremental) 1 else 0, 0, 0, 0, 0,
                (w shr 8).toByte(), w.toByte(), (h shr 8).toByte(), h.toByte(),
            ),
        )
    }

    private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun send(data: ByteArray) {
        val o = out ?: return
        try {
            sender.execute {
                try { synchronized(o) { o.write(data); o.flush() } } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    // ---- input ----
    fun pointer(x: Int, y: Int, mask: Int) {
        if (!connected) return
        val cx = x.coerceIn(0, (fbW - 1).coerceAtLeast(0))
        val cy = y.coerceIn(0, (fbH - 1).coerceAtLeast(0))
        send(byteArrayOf(5, mask.toByte(), (cx shr 8).toByte(), cx.toByte(), (cy shr 8).toByte(), cy.toByte()))
    }

    fun key(keysym: Int, down: Boolean) {
        if (!connected) return
        send(byteArrayOf(4, if (down) 1 else 0, 0, 0) + int32(keysym))
    }

    fun press(keysym: Int) {
        key(keysym, true)
        key(keysym, false)
    }
}
