package com.kalibox.app

import com.kalibox.app.util.ArchivePaths
import com.kalibox.app.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtilTest {
    @Test fun cleanNormalizes() {
        assertEquals("etc/passwd", ArchivePaths.clean("./etc/passwd"))
        assertEquals("a/b", ArchivePaths.clean("/a//b"))
    }

    @Test fun cleanRejectsTraversal() {
        assertNull(ArchivePaths.clean("../evil"))
        assertNull(ArchivePaths.clean("a/../../evil"))
    }

    @Test fun topPrefixAndStrip() {
        assertEquals("kali-arm64", ArchivePaths.topPrefix("kali-arm64/bin"))
        assertNull(ArchivePaths.topPrefix("bin/bash"))
        assertEquals("bin/bash", ArchivePaths.strip("kali-arm64/bin/bash", "kali-arm64"))
        assertEquals("", ArchivePaths.strip("kali-arm64", "kali-arm64"))
        assertEquals("bin/bash", ArchivePaths.strip("bin/bash", null))
    }

    @Test fun bytesFormat() {
        assertEquals("512 B", Fmt.bytes(512))
        assertEquals("1.5 KB", Fmt.bytes(1536))
        assertEquals("1.0 GB", Fmt.bytes(1024L * 1024 * 1024))
    }
}
