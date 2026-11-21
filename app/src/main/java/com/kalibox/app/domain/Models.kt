package com.kalibox.app.domain

enum class Stage { CHECK, DOWNLOAD, VERIFY, EXTRACT, CONFIGURE, DONE }

data class InstallProgress(
    val stage: Stage = Stage.CHECK,
    val percent: Int = -1,
    val message: String = "",
    val bytes: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,
    val paused: Boolean = false,
    val error: String? = null,
    val running: Boolean = false,
)

class InstallException(message: String) : Exception(message)

enum class Screen { LEGAL, SETUP, HOME, TERMINAL, DESKTOP_SETUP, DESKTOP, SETTINGS, TASK }

data class TaskUi(
    val title: String = "",
    val lines: List<String> = emptyList(),
    val running: Boolean = false,
    val exitCode: Int? = null,
)

data class ToolItem(val label: String, val packages: List<String>)

object Tools {
    val all = listOf(
        ToolItem("kali-tools-top10", listOf("kali-tools-top10")),
        ToolItem("nmap", listOf("nmap")),
        ToolItem("metasploit-framework", listOf("metasploit-framework")),
        ToolItem("sqlmap", listOf("sqlmap")),
        ToolItem("hydra", listOf("hydra")),
        ToolItem("john", listOf("john")),
        ToolItem("wireshark-cli (tshark)", listOf("tshark")),
        ToolItem("python3-pip", listOf("python3-pip")),
        ToolItem("git", listOf("git")),
        ToolItem("nano", listOf("nano")),
        ToolItem("vim", listOf("vim")),
        ToolItem("wireless: aircrack-ng, iw, wireless-tools, reaver, wifite", listOf("aircrack-ng", "iw", "wireless-tools", "reaver", "wifite", "macchanger")),
        ToolItem("usb/bluetooth: usbutils, pciutils, bluez", listOf("usbutils", "pciutils", "bluez")),
    )
}
