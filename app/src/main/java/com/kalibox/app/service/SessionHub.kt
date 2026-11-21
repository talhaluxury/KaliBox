package com.kalibox.app.service

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.kalibox.app.data.Prefs
import com.kalibox.app.data.Proot
import com.kalibox.app.util.AppLog
import com.kalibox.app.util.AppPaths
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Properties

/** Owns all terminal sessions so they survive screen changes. */
object SessionHub {
    private lateinit var app: Application
    private val paths by lazy { AppPaths(app) }
    private val prefs by lazy { Prefs(app) }
    private val proot by lazy { Proot(app, paths, prefs) }

    val sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val active = MutableStateFlow(0)
    val tick = MutableStateFlow(0)
    val ctrl = MutableStateFlow(false)
    val alt = MutableStateFlow(false)
    val fontSp = MutableStateFlow(14)

    @Volatile var view: TerminalView? = null
    var onNewTabRequest: (() -> Unit)? = null

    fun init(a: Application) {
        app = a
        fontSp.value = prefs.fontSize
    }

    val schemes: Map<String, Map<String, String>> = mapOf(
        "Kali dark" to mapOf("background" to "#0B0F14", "foreground" to "#E6EDF3", "cursor" to "#367BF0"),
        "Matrix" to mapOf("background" to "#000000", "foreground" to "#00FF41", "cursor" to "#00FF41"),
        "Solarized dark" to mapOf("background" to "#002B36", "foreground" to "#93A1A1", "cursor" to "#93A1A1"),
        "Light" to mapOf("background" to "#FDF6E3", "foreground" to "#073642", "cursor" to "#073642"),
    )

    fun applyScheme(name: String) {
        val p = Properties()
        (schemes[name] ?: schemes.getValue("Kali dark")).forEach { (k, v) -> p[k] = v }
        TerminalColors.COLOR_SCHEME.updateWith(p)
        sessions.value.forEach { it.emulator?.mColors?.reset() }
        view?.onScreenUpdated()
        prefs.colorScheme = name
    }

    fun newSession() {
        proot.prepare()
        val args = proot.sessionArgs()
        applyScheme(prefs.colorScheme)
        val s = TerminalSession(proot.launcher(), paths.files.path, args.toTypedArray(), proot.hostEnvArray(), 2000, sessionClient)
        sessions.update { it + s }
        active.value = sessions.value.lastIndex
        KaliService.start(app)
    }

    fun close(index: Int) {
        val s = sessions.value.getOrNull(index) ?: return
        s.finishIfRunning()
        removeSession(s)
    }

    fun closeAll() {
        sessions.value.forEach { it.finishIfRunning() }
        sessions.value = emptyList()
        active.value = 0
    }

    private fun removeSession(s: TerminalSession) {
        sessions.update { it - s }
        active.value = active.value.coerceIn(0, (sessions.value.size - 1).coerceAtLeast(0))
        tick.update { it + 1 }
        KaliService.refresh(app)
    }

    fun current(): TerminalSession? = sessions.value.getOrNull(active.value)
    fun send(text: String) { current()?.write(text) }

    fun paste() {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
        if (!t.isNullOrEmpty()) current()?.emulator?.paste(t)
    }

    fun changeFont(delta: Int) {
        val v = (fontSp.value + delta).coerceIn(8, 32)
        fontSp.value = v
        prefs.fontSize = v
    }

    fun fontPx(): Int = (fontSp.value * app.resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- clients
    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { view?.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) { tick.update { it + 1 } }
        override fun onSessionFinished(finishedSession: TerminalSession) { removeSession(finishedSession) }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("KaliBox", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) { paste() }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String?, message: String?) { AppLog.e(tag ?: "term", message ?: "") }
        override fun logWarn(tag: String?, message: String?) { AppLog.i(tag ?: "term", "W: $message") }
        override fun logInfo(tag: String?, message: String?) { AppLog.i(tag ?: "term", message ?: "") }
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { AppLog.e(tag ?: "term", message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { AppLog.e(tag ?: "term", "stack", e) }
    }

    val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                changeFont(if (scale > 1f) 1 else -1)
                view?.setTextSize(fontPx())
                return 1.0f
            }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) {
            val v = view ?: return
            v.requestFocus()
            (app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            if (e == null) return false
            // Hardware keyboard shortcuts
            if (e.isCtrlPressed && e.isShiftPressed) {
                when (keyCode) {
                    KeyEvent.KEYCODE_V -> { paste(); return true }
                    KeyEvent.KEYCODE_T -> { onNewTabRequest?.invoke(); return true }
                    KeyEvent.KEYCODE_W -> { close(active.value); return true }
                }
            }
            if (e.isAltPressed && keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                val idx = keyCode - KeyEvent.KEYCODE_1
                if (idx < sessions.value.size) { active.value = idx; return true }
            }
            if (e.isCtrlPressed && (keyCode == KeyEvent.KEYCODE_PLUS || keyCode == KeyEvent.KEYCODE_EQUALS)) {
                changeFont(1); view?.setTextSize(fontPx()); return true
            }
            if (e.isCtrlPressed && keyCode == KeyEvent.KEYCODE_MINUS) {
                changeFont(-1); view?.setTextSize(fontPx()); return true
            }
            return false
        }
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean { val v = ctrl.value; if (v) ctrl.value = false; return v }
        override fun readAltKey(): Boolean { val v = alt.value; if (v) alt.value = false; return v }
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) { AppLog.e(tag ?: "view", message ?: "") }
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { AppLog.e(tag ?: "view", message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { AppLog.e(tag ?: "view", "stack", e) }
    }
}
