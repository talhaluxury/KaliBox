package com.kalibox.app

import com.kalibox.app.data.VncAuth
import org.junit.Assert.assertEquals
import org.junit.Test

class VncAuthTest {
    @Test fun keyReversesBitsAndPads() {
        // 'a' = 0x61 = 01100001 -> reversed 10000110 = 0x86 ; missing bytes are 0
        val k = VncAuth.key("a")
        assertEquals(0x86.toByte(), k[0])
        assertEquals(0.toByte(), k[1])
        assertEquals(8, k.size)
    }

    @Test fun responseMatchesOpenSsl() {
        val challenge = ByteArray(16) { it.toByte() }
        val out = VncAuth.response("abcd1234", challenge).joinToString("") { "%02x".format(it) }
        assertEquals("fcc310c13b8d36be29db135cef1b870a", out)
    }
}
