package com.kalibox.app.data

import android.system.Os
import java.io.File

/** First-run fixes applied to a freshly extracted rootfs. */
object RootfsConfigurator {
    fun apply(rootfs: File) {
        fun write(path: String, text: String, mode: Int = 420 /* 0644 */) {
            val f = File(rootfs, path)
            f.parentFile?.mkdirs()
            f.delete() // may be a dangling symlink (resolv.conf)
            f.writeText(text)
            Os.chmod(f.path, mode)
        }
        fun dir(path: String, mode: Int) {
            val d = File(rootfs, path)
            d.mkdirs()
            Os.chmod(d.path, mode)
        }

        write("etc/resolv.conf", "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        write("etc/hosts", "127.0.0.1 localhost\n127.0.1.1 kali\n::1 localhost ip6-localhost ip6-loopback\n")
        write("etc/hostname", "kali\n")
        // Newer rootfs images ship /etc/apt/sources.list.d/kali.sources: do not configure the same repo twice
        if (File(rootfs, "etc/apt/sources.list.d/kali.sources").exists()) {
            write("etc/apt/sources.list", "# Kali repository is configured in /etc/apt/sources.list.d/kali.sources\n")
        } else {
            write(
                "etc/apt/sources.list",
                "deb http://http.kali.org/kali kali-rolling main contrib non-free non-free-firmware\n",
            )
        }
        write(
            "etc/apt/apt.conf.d/99kalibox",
            "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\nDpkg::Progress-Fancy \"0\";\n" +
                "Dpkg::Options { \"--force-confdef\"; \"--force-confold\"; };\n",
        )
        // Services cannot run in proot (no init system): block maintainer scripts from starting them.
        write("usr/sbin/policy-rc.d", "#!/bin/sh\nexit 101\n", 493 /* 0755 */)
        write(
            "etc/profile.d/kalibox.sh",
            "export LANG=C.UTF-8\nexport MOZ_DISABLE_CONTENT_SANDBOX=1\nexport MOZ_FAKE_NO_SANDBOX=1\n",
        )
        dir("tmp", 511 /* 0777 */)
        dir("var/tmp", 511)
        dir("run", 493)
        dir("run/lock", 493)
        dir("dev/shm", 511)
        dir("root", 448 /* 0700 */)
        dir("root/.cache", 448)
        dir("root/.kalibox", 448)
    }
}
