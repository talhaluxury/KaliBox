package com.kalibox.app.data

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** RFB "VNC Authentication": DES-encrypt the 16-byte challenge with the password as key (bits of each key byte reversed). */
object VncAuth {
    fun key(password: String): ByteArray {
        val raw = password.toByteArray(Charsets.ISO_8859_1)
        val key = ByteArray(8)
        for (i in 0 until 8) {
            val b = if (i < raw.size) raw[i].toInt() and 0xFF else 0
            key[i] = (Integer.reverse(b) ushr 24).toByte()
        }
        return key
    }

    fun response(password: String, challenge: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(password), "DES"))
        return cipher.doFinal(challenge)
    }
}
