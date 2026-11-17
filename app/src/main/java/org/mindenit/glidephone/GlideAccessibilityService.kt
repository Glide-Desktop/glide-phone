package org.mindenit.glidephone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sign

/**
 * Lets a connected computer drive the phone (mousereceiver + remotekeyboard), after KDE
 * Connect's KdeConnectAccessibilityService and RemoteKeyboardPlugin: an overlay cursor that
 * taps, long-presses, drags and scrolls through dispatched gestures, and keyboard input into
 * the focused field.
 */
class GlideAccessibilityService : AccessibilityService() {
    private lateinit var windowManager: WindowManager
    private var cursorView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { cursorView?.visibility = View.GONE }
    private var swipeStroke: GestureDescription.StrokeDescription? = null
    private var scrollSum = 0.0
    private var x = 0
    private var y = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private val cursorSize by lazy { (24 * resources.displayMetrics.density).toInt() }

    private val cursorLayout = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        gravity = Gravity.START or Gravity.TOP
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onServiceConnected() {
        instance = this
        cursorView = CursorView(this).apply { visibility = View.GONE }
        try {
            windowManager.addView(cursorView, cursorLayout.apply { width = cursorSize; height = cursorSize })
        } catch (_: WindowManager.BadTokenException) {
            cursorView = null
        }
        updateScreenBounds()
        x = screenWidth / 2
        y = screenHeight / 2
        RemoteInput.keyboardAvailabilityChanged(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenBounds()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        cursorView?.let { runCatching { windowManager.removeView(it) } }
        RemoteInput.keyboardAvailabilityChanged(this)
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        RemoteInput.keyboardAvailabilityChanged(this)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    @Suppress("DEPRECATION")
    private fun updateScreenBounds() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    // ---- Mouse (MouseReceiverPlugin) ----

    val cursorX get() = x
    val cursorY get() = y
    val width get() = screenWidth
    val height get() = screenHeight

    fun hide(delayMillis: Long) {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, delayMillis)
    }

    private fun moveView(dx: Int, dy: Int) {
        x = (x + dx).coerceIn(0, screenWidth)
        y = (y + dy).coerceIn(0, screenHeight)
        cursorLayout.x = x - cursorSize / 2
        cursorLayout.y = y - cursorSize / 2
        handler.post {
            val view = cursorView ?: return@post
            runCatching { windowManager.updateViewLayout(view, cursorLayout) }
            view.visibility = View.VISIBLE
        }
    }

    fun move(dx: Int, dy: Int): Boolean {
        val fromX = x
        val fromY = y
        moveView(dx, dy)
        hide(5000)
        if (Build.VERSION.SDK_INT >= 26 && swipeStroke != null) return continueSwipe(fromX, fromY)
        return true
    }

    fun setPos(newX: Int, newY: Int): Boolean = move(newX - x, newY - y)

    fun click(): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && swipeStroke != null) return stopSwipe()
        return dispatchGesture(tap(x, y, 1), null, null)
    }

    fun longClick(): Boolean = dispatchGesture(tap(x, y, ViewConfiguration.getLongPressTimeout().toLong()), null, null)

    /** Press-and-hold starts a drag, a second hold ends it (KDE Connect's longClickSwipe). */
    fun longClickSwipe(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return longClick()
        return if (swipeStroke != null) stopSwipe() else startSwipe()
    }

    private fun startSwipe(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 1, true)
        swipeStroke = stroke
        (cursorView as? CursorView)?.dragging = true
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun continueSwipe(fromX: Int, fromY: Int): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val current = swipeStroke ?: return false
        val path = Path().apply { moveTo(fromX.toFloat(), fromY.toFloat()); lineTo(x.toFloat(), y.toFloat()) }
        val next = current.continueStroke(path, 0, 5, true)
        swipeStroke = next
        return dispatchGesture(GestureDescription.Builder().addStroke(next).build(), null, null)
    }

    fun stopSwipe(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        val stroke = swipeStroke ?: return true
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val last = stroke.continueStroke(path, 0, 1, false)
        swipeStroke = null
        (cursorView as? CursorView)?.dragging = false
        return dispatchGesture(GestureDescription.Builder().addStroke(last).build(), null, null)
    }

    fun scroll(dy: Int): Boolean {
        scrollSum += dy.toDouble()
        if (sign(dy.toDouble()) != sign(scrollSum)) scrollSum = dy.toDouble()
        if (abs(scrollSum) < 500) return false
        scrollSum = 0.0
        val action = if (dy > 0) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD
        return findNodeByAction(rootInActiveWindow, action)?.performAction(action.id) ?: false
    }

    fun findNodeByAction(root: AccessibilityNodeInfo?, action: AccessibilityNodeInfo.AccessibilityAction): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.actionList.contains(action)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    // ---- Keyboard (RemoteKeyboardPlugin) ----

    /** @return true when the key was handled. */
    fun handleKey(key: String, specialKey: Int, shift: Boolean, ctrl: Boolean): Boolean {
        if (key.isEmpty() && specialKey == 0) return false
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus == null) {
            if (specialKey == SpecialKey.TAB) {
                findNodeByAction(rootInActiveWindow, AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
                    ?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                return true
            }
            return false
        }
        when {
            shift || ctrl -> multiKey(focus, key, specialKey, shift, ctrl)
            specialKey != 0 -> specialKey(focus, specialKey)
            else -> keyInput(focus, key)
        }
        return true
    }

    private fun multiKey(focus: AccessibilityNodeInfo, key: String, specialKey: Int, shift: Boolean, ctrl: Boolean) {
        if (shift) {
            val movement = if (ctrl) AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD else AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
            when (specialKey) {
                SpecialKey.TAB -> moveFocus(focus, forward = false)
                SpecialKey.LEFT -> moveCursor(focus, false, movement, select = true)
                SpecialKey.RIGHT -> moveCursor(focus, true, movement, select = true)
                SpecialKey.HOME, SpecialKey.UP -> moveCursor(focus, false, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE, select = true)
                SpecialKey.END, SpecialKey.DOWN -> moveCursor(focus, true, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE, select = true)
                SpecialKey.ENTER -> keyInput(focus, "\n")
                SpecialKey.NONE -> keyInput(focus, key)
            }
        } else if (ctrl) {
            when (key) {
                "c" -> focus.performAction(AccessibilityNodeInfo.ACTION_COPY)
                "v" -> focus.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                "x" -> focus.performAction(AccessibilityNodeInfo.ACTION_CUT)
                "a" -> setSelection(focus, 0, focus.text?.length ?: 0)
            }
            when (specialKey) {
                SpecialKey.LEFT -> moveCursor(focus, false, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD)
                SpecialKey.RIGHT -> moveCursor(focus, true, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD)
                SpecialKey.BACKSPACE -> delete(focus, forward = false, words = true)
                SpecialKey.DELETE -> delete(focus, forward = true, words = true)
                SpecialKey.ENTER -> if (focus.isEditable && Build.VERSION.SDK_INT >= 33) {
                    focus.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else if (Build.VERSION.SDK_INT >= 30) {
                    performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
                }
            }
        }
    }

    private fun specialKey(focus: AccessibilityNodeInfo, specialKey: Int) {
        val line = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
        when (specialKey) {
            SpecialKey.TAB -> moveFocus(focus, forward = true)
            SpecialKey.BACKSPACE -> delete(focus, forward = false)
            SpecialKey.DELETE -> delete(focus, forward = true)
            SpecialKey.END -> moveCursor(focus, true, line)
            SpecialKey.HOME -> moveCursor(focus, false, line)
            SpecialKey.LEFT -> if (Build.VERSION.SDK_INT >= 33) performGlobalAction(GLOBAL_ACTION_DPAD_LEFT) else moveCursor(focus, false)
            SpecialKey.RIGHT -> if (Build.VERSION.SDK_INT >= 33) performGlobalAction(GLOBAL_ACTION_DPAD_RIGHT) else moveCursor(focus, true)
            SpecialKey.UP -> if (Build.VERSION.SDK_INT >= 33) performGlobalAction(GLOBAL_ACTION_DPAD_UP) else moveCursor(focus, false, line)
            SpecialKey.DOWN -> if (Build.VERSION.SDK_INT >= 33) performGlobalAction(GLOBAL_ACTION_DPAD_DOWN) else moveCursor(focus, true, line)
            SpecialKey.ENTER -> if (focus.isEditable && Build.VERSION.SDK_INT >= 33) {
                performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
            } else if (Build.VERSION.SDK_INT >= 30) {
                focus.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            SpecialKey.ESCAPE -> if (Build.VERSION.SDK_INT >= 33) performGlobalAction(GLOBAL_ACTION_BACK)
            else focus.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        }
    }

    private fun moveFocus(focus: AccessibilityNodeInfo, forward: Boolean) {
        focus.focusSearch(if (forward) View.FOCUS_FORWARD else View.FOCUS_BACKWARD)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun keyInput(focus: AccessibilityNodeInfo, key: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && focus.isEditable) {
            inputMethod?.currentInputConnection?.let { it.commitText(key, 1, null); return true }
        }
        val text = fieldText(focus) ?: ""
        val first = minOf(focus.textSelectionStart, focus.textSelectionEnd).coerceIn(0, text.length)
        val last = maxOf(focus.textSelectionStart, focus.textSelectionEnd).coerceIn(0, text.length)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text.substring(0, first) + key + text.substring(last))
        }
        if (!focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        if (text.isNotEmpty()) setSelection(focus, first + key.length, first + key.length)
        return true
    }

    private fun delete(focus: AccessibilityNodeInfo, forward: Boolean, words: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 && focus.isEditable) {
            inputMethod?.currentInputConnection?.let { connection ->
                val code = if (forward) KeyEvent.KEYCODE_FORWARD_DEL else KeyEvent.KEYCODE_DEL
                val now = SystemClock.uptimeMillis()
                val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, if (words) KeyEvent.META_CTRL_ON else 0)
                connection.sendKeyEvent(down)
                connection.sendKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
                return
            }
        }
        val text = fieldText(focus) ?: return
        val first = minOf(focus.textSelectionStart, focus.textSelectionEnd).coerceIn(0, text.length)
        val last = maxOf(focus.textSelectionStart, focus.textSelectionEnd).coerceIn(0, text.length)
        val hasSelection = first != last
        var drop = when {
            hasSelection -> 0
            words && forward -> text.indexOf(' ', first + 1, true) - first
            words -> if (first == 0) 0 else first - text.substring(0, first - 1).indexOfLast { it.isWhitespace() } - 1
            else -> 1
        }
        if (drop < 0 && forward) drop = text.length - first
        val head = text.substring(0, first)
        val tail = text.substring(last)
        val newText = if (forward) head + tail.drop(drop) else head.dropLast(drop) + tail
        focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        })
        val pos = if (hasSelection || forward) first else (first - drop).coerceAtLeast(0)
        setSelection(focus, pos, pos)
    }

    private fun setSelection(node: AccessibilityNodeInfo, start: Int, end: Int) {
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start.coerceAtLeast(0))
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end.coerceAtLeast(0))
        })
    }

    private fun moveCursor(focus: AccessibilityNodeInfo, forward: Boolean,
                           granularity: Int = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER, select: Boolean = false) {
        focus.performAction(
            if (forward) AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY.id
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY.id,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
                putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, select)
            }
        )
    }

    private fun fieldText(focus: AccessibilityNodeInfo): String? {
        if (Build.VERSION.SDK_INT >= 26 && focus.isShowingHintText) return null
        if (focus.textSelectionStart == -1 && focus.textSelectionEnd == -1) return null
        return focus.text?.toString()
    }

    /** Small arrow pointer drawn without resources. */
    @SuppressLint("ViewConstructor")
    private class CursorView(context: Context) : View(context) {
        var dragging = false
            set(value) { field = value; postInvalidate() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.BLACK }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val arrow = Path().apply {
                moveTo(w * 0.15f, h * 0.05f); lineTo(w * 0.15f, h * 0.85f); lineTo(w * 0.38f, h * 0.65f)
                lineTo(w * 0.55f, h * 0.98f); lineTo(w * 0.68f, h * 0.92f); lineTo(w * 0.52f, h * 0.6f)
                lineTo(w * 0.85f, h * 0.6f); close()
            }
            fill.color = if (dragging) Color.rgb(96, 66, 186) else Color.WHITE
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, outline)
        }
    }

    companion object {
        @Volatile var instance: GlideAccessibilityService? = null
            private set

        private fun tap(x: Int, y: Int, duration: Long): GestureDescription {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            return GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        }
    }
}

/** KDE Connect special key codes (RemoteKeyboardEnumClass.SpecialKeys). */
internal object SpecialKey {
    const val NONE = 0
    const val BACKSPACE = 1
    const val TAB = 2
    const val LEFT = 4
    const val UP = 5
    const val RIGHT = 6
    const val DOWN = 7
    const val PAGE_UP = 8
    const val PAGE_DOWN = 9
    const val HOME = 10
    const val END = 11
    const val ENTER = 12
    const val DELETE = 13
    const val ESCAPE = 14
    const val SYSRQ = 15
    const val SCROLL_LOCK = 16
}
