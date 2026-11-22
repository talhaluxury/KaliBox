package com.kalibox.app.util

/** Central place for everything you might need to update later. */
object Constants {
    /** Kali NetHunter arm64 rootfs. Change this URL to use the "full" or "nano" variant. */
    const val ROOTFS_URL =
        "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz"

    /**
     * Pin the SHA256 of the rootfs here for strict verification.
     * If empty, the app fetches ROOTFS_SHA256_URL and looks up the file name in it.
     * If that also fails, install continues with a visible "unverified" warning.
     */
    const val ROOTFS_SHA256 = ""
    const val ROOTFS_SHA256_URL = "https://kali.download/nethunter-images/current/rootfs/SHA256SUMS"

    const val MIN_FREE_ROOTFS_BYTES = 6L * 1024 * 1024 * 1024
    const val MIN_FREE_DESKTOP_BYTES = 4L * 1024 * 1024 * 1024
    /** Android reports ~3.6 GiB for a "4 GB" phone, so use 3.5 GiB as the 4 GB threshold. */
    const val MIN_RAM_DESKTOP_BYTES = 3584L * 1024 * 1024

    const val VNC_DISPLAY = ":1"
    const val VNC_PORT = 5901
    const val NOVNC_PORT = 6080
    const val GUEST_DIR = "root/.kalibox"
}
