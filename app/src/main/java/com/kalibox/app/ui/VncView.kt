package com.kalibox.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.kalibox.app.data.VncClient
import com.kalibox.app.util.Constants
import kotlin.math.hypot

/** Draws the remote desktop and turns touches / keyboard input into VNC events. */
class VncView(context: Context) : View(context), VncClient.Listener {
    var client: VncClient? = null
        private set
    var touchpad = false
        set(v) { field = v; invalidate() }
    var holdLeft = false

    @Volatile private var status = "Connecting…"
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 42f; textAlign = Paint.Align.CENTER }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val cursorPath = Path()

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private var px = 100f
    private var py = 100f

    private val handler = Handler(Looper.getMainLooper())
    private var longPress: Runnable? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastAvgY = 0f
    private var scrollAcc = 0f
    private var downTime = 0L
    private var moved = false
    private var multi = false
    private var longFired = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        setBackgroundColor(Color.BLACK)
    }

    fun start(password: String) {
        stop()
        client = VncClient("127.0.0.1", Constants.VNC_PORT, password, this).also { it.start() }
    }

    fun stop() {
        client?.close()
        client = null
    }

    override fun onStatus(text: String) { status = text; postInvalidate() }
    override fun onUpdate() { postInvalidate() }
    override fun onFbSize(w: Int, h: Int) { px = w / 2f; py = h / 2f; postInvalidate() }

    // ------------------------------------------------------------------ drawing
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val cl = client
        if (cl != null) {
            synchronized(cl.lock) {
                val b = cl.bitmap
                if (b != null && width > 0 && height > 0) {
                    scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
                    offX = (width - b.width * scale) / 2f
                    offY = (height - b.height * scale) / 2f
                    canvas.save()
                    canvas.translate(offX, offY)
                    canvas.scale(scale, scale)
                    canvas.drawBitmap(b, 0f, 0f, paint)
                    canvas.restore()
                }
            }
            if (touchpad && cl.connected) drawCursor(canvas, offX + px * scale, offY + py * scale)
        }
        if (status.isNotEmpty()) canvas.drawText(status, width / 2f, height / 2f, textPaint)
    }

    private fun drawCursor(c: Canvas, x: Float, y: Float) {
        cursorPath.reset()
        cursorPath.moveTo(x, y)
        cursorPath.lineTo(x, y + 34f)
        cursorPath.lineTo(x + 9f, y + 26f)
        cursorPath.lineTo(x + 18f, y + 40f)
        cursorPath.lineTo(x + 24f, y + 37f)
        cursorPath.lineTo(x + 15f, y + 23f)
        cursorPath.lineTo(x + 26f, y + 23f)
        cursorPath.close()
        c.drawPath(cursorPath, fillPaint)
        c.drawPath(cursorPath, strokePaint)
    }

    // ------------------------------------------------------------------ touch
    private fun fbW() = client?.fbW ?: 0
    private fun fbH() = client?.fbH ?: 0

    private fun movePointerTo(x: Float, y: Float) {
        px = ((x - offX) / scale).coerceIn(0f, (fbW() - 1).coerceAtLeast(0).toFloat())
        py = ((y - offY) / scale).coerceIn(0f, (fbH() - 1).coerceAtLeast(0).toFloat())
    }

    private fun sendMask(mask: Int) { client?.pointer(px.toInt(), py.toInt(), mask) }

    private fun cancelLongPress() {
        longPress?.let { handler.removeCallbacks(it) }
        longPress = null
    }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    private fun avgX(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getX(i)
        return s / e.pointerCount
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (client == null) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false; multi = false; longFired = false; downTime = e.eventTime
                if (!touchpad) { movePointerTo(e.x, e.y); sendMask(1) }
                val r = Runnable {
                    if (!moved && !multi) {
                        longFired = true
                        sendMask(0); sendMask(4); sendMask(0) // right click
                    }
                }
                longPress = r
                handler.postDelayed(r, 600)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!multi) {
                    multi = true
                    cancelLongPress()
                    if (!touchpad) sendMask(0)
                }
                lastAvgY = avgY(e)
                scrollAcc = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val a = avgY(e)
                    scrollAcc += a - lastAvgY
                    lastAvgY = a
                    if (!touchpad) movePointerTo(avgX(e), a)
                    while (scrollAcc >= 40f) { sendMask(8); sendMask(0); scrollAcc -= 40f }   // wheel up
                    while (scrollAcc <= -40f) { sendMask(16); sendMask(0); scrollAcc += 40f } // wheel down
                } else if (!multi) {
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) { moved = true; cancelLongPress() }
                    if (touchpad) {
                        if (moved) {
                            px = (px + (e.x - lastX) / scale * 1.6f).coerceIn(0f, (fbW() - 1).coerceAtLeast(0).toFloat())
                            py = (py + (e.y - lastY) / scale * 1.6f).coerceIn(0f, (fbH() - 1).coerceAtLeast(0).toFloat())
                            sendMask(if (holdLeft) 1 else 0)
                            invalidate()
                        }
                    } else if (moved && !longFired) {
                        movePointerTo(e.x, e.y)
                        sendMask(1)
                    }
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                if (!multi && !longFired) {
                    if (touchpad) {
                        if (e.actionMasked == MotionEvent.ACTION_UP && !moved && e.eventTime - downTime < 350) {
                            sendMask(1); sendMask(if (holdLeft) 1 else 0)
                        }
                    } else {
                        sendMask(0) // release = end of click / drag
                    }
                }
                multi = false
            }
        }
        return true
    }

    // ------------------------------------------------------------------ keyboard
    private var composing = ""

    private fun symForChar(cp: Int): Int = when {
        cp == '\n'.code -> 0xFF0D
        cp == '\t'.code -> 0xFF09
        cp < 0x100 -> cp
        else -> 0x01000000 + cp
    }

    private fun typeText(text: CharSequence) {
        val cl = client ?: return
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            cl.press(symForChar(cp))
            i += Character.charCount(cp)
        }
    }

    private fun backspaces(n: Int) { repeat(n) { client?.press(0xFF08) } }

    private fun keysymFor(ev: KeyEvent): Int = when (ev.keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xFF0D
        KeyEvent.KEYCODE_DEL -> 0xFF08
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF
        KeyEvent.KEYCODE_TAB -> 0xFF09
        KeyEvent.KEYCODE_ESCAPE -> 0xFF1B
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
        KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
        KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
        KeyEvent.KEYCODE_MOVE_HOME -> 0xFF50
        KeyEvent.KEYCODE_MOVE_END -> 0xFF57
        KeyEvent.KEYCODE_PAGE_UP -> 0xFF55
        KeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xFFE1
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xFFE2
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xFFE3
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xFFE4
        KeyEvent.KEYCODE_ALT_LEFT -> 0xFFE9
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xFFEA
        KeyEvent.KEYCODE_META_LEFT -> 0xFFEB
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER -> 0
        else -> {
            val u = ev.getUnicodeChar((ev.metaState and KeyEvent.META_SHIFT_MASK) or (ev.metaState and KeyEvent.META_CAPS_LOCK_ON))
            if (u > 0) symForChar(u) else 0
        }
    }

    private fun handleKey(ev: KeyEvent, down: Boolean): Boolean {
        val sym = keysymFor(ev)
        if (sym == 0) return false
        client?.key(sym, down)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event, true) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = handleKey(event, false) || super.onKeyUp(keyCode, event)

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val t = text?.toString() ?: ""
                if (composing.isNotEmpty()) {
                    if (t.startsWith(composing)) typeText(t.substring(composing.length))
                    else { backspaces(composing.length); typeText(t) }
                    composing = ""
                } else typeText(t)
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val t = text?.toString() ?: ""
                if (t.startsWith(composing)) typeText(t.substring(composing.length))
                else { backspaces(composing.length); typeText(t) }
                composing = t
                return true
            }

            override fun finishComposingText(): Boolean { composing = ""; return true }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    backspaces(beforeLength)
                    composing = composing.dropLast(minOf(beforeLength, composing.length))
                }
                repeat(afterLength) { client?.press(0xFFFF) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                return when (event.action) {
                    KeyEvent.ACTION_DOWN -> handleKey(event, true)
                    KeyEvent.ACTION_UP -> handleKey(event, false)
                    else -> false
                }
            }
        }
    }
}
