package org.mindenit.glidephone

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Input method that types what the computer's keyboard sends (KDE Connect's
 * RemoteKeyboardService + RemoteKeyboardIMEPlugin). Its own view only offers hide,
 * switch-keyboard and a connection indicator.
 */
class GlideKeyboardService : InputMethodService() {
    @Volatile var visible = false
        private set
    private var status: TextView? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        visible = false
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val pad = (12 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(Button(context).apply { text = "Сховати"; setOnClickListener { requestHideSelf(0) } })
            addView(Button(context).apply {
                text = "Клавіатура"
                setOnClickListener { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
            })
            status = TextView(context).apply { setPadding(pad, 0, 0, 0) }
            addView(status)
            updateStatus()
        }
    }

    fun updateStatus() {
        status?.post {
            status?.text = if (GlideHub.connectedDevices.isNotEmpty()) "Glide Phone: підключено" else "Glide Phone: не підключено"
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        visible = true
        updateStatus()
        window?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RemoteInput.keyboardAvailabilityChanged(this)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        visible = false
        window?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RemoteInput.keyboardAvailabilityChanged(this)
    }

    /** RemoteKeyboardIMEPlugin.handleEvent. */
    fun handleKey(key: String, specialKey: Int, shift: Boolean, ctrl: Boolean): Boolean {
        val keyCode = SPECIAL_KEYS[specialKey]
        return if (keyCode != null) handleSpecial(keyCode, shift, ctrl) else handleVisible(key, ctrl)
    }

    private fun handleSpecial(keyCode: Int, shift: Boolean, ctrl: Boolean): Boolean {
        val connection = currentInputConnection ?: return false
        if (ctrl && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            val forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return false
            val text = extracted.text?.toString() ?: return false
            val cursor = extracted.selectionEnd
            val space = if (forward) text.indexOf(' ', cursor) else text.lastIndexOf(' ', cursor - 2)
            val pos = when { space != -1 -> space + 1; forward -> text.length; else -> 0 }
            connection.setSelection(if (shift) extracted.selectionStart else pos, pos)
        } else if (shift && keyCode in SHIFTABLE) {
            val now = SystemClock.uptimeMillis()
            connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
            connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_SHIFT_LEFT_ON))
            connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, KeyEvent.META_SHIFT_LEFT_ON))
            connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
        } else if (keyCode == KeyEvent.KEYCODE_ENTER) {
            val info = currentInputEditorInfo
            if (info != null && ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 || ctrl)) {
                val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
                return when (action) {
                    EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_SEARCH -> {
                        connection.performEditorAction(action); true
                    }
                    else -> false
                }
            }
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } else {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        return true
    }

    private fun handleVisible(key: String, ctrl: Boolean): Boolean {
        if (key.isEmpty()) return false
        val connection = currentInputConnection ?: return false
        if (ctrl) {
            when (key.lowercase()) {
                "c" -> return connection.performContextMenuAction(android.R.id.copy)
                "v" -> return connection.performContextMenuAction(android.R.id.paste)
                "x" -> return connection.performContextMenuAction(android.R.id.cut)
                "a" -> return connection.performContextMenuAction(android.R.id.selectAll)
            }
        }
        connection.commitText(key, key.length)
        return true
    }

    companion object {
        @Volatile var instance: GlideKeyboardService? = null
            private set

        private val SPECIAL_KEYS = mapOf(
            1 to KeyEvent.KEYCODE_DEL, 2 to KeyEvent.KEYCODE_TAB, 4 to KeyEvent.KEYCODE_DPAD_LEFT,
            5 to KeyEvent.KEYCODE_DPAD_UP, 6 to KeyEvent.KEYCODE_DPAD_RIGHT, 7 to KeyEvent.KEYCODE_DPAD_DOWN,
            8 to KeyEvent.KEYCODE_PAGE_UP, 9 to KeyEvent.KEYCODE_PAGE_DOWN, 10 to KeyEvent.KEYCODE_MOVE_HOME,
            11 to KeyEvent.KEYCODE_MOVE_END, 12 to KeyEvent.KEYCODE_ENTER, 13 to KeyEvent.KEYCODE_FORWARD_DEL,
            14 to KeyEvent.KEYCODE_ESCAPE, 15 to KeyEvent.KEYCODE_SYSRQ, 16 to KeyEvent.KEYCODE_SCROLL_LOCK,
            21 to KeyEvent.KEYCODE_F1, 22 to KeyEvent.KEYCODE_F2, 23 to KeyEvent.KEYCODE_F3, 24 to KeyEvent.KEYCODE_F4,
            25 to KeyEvent.KEYCODE_F5, 26 to KeyEvent.KEYCODE_F6, 27 to KeyEvent.KEYCODE_F7, 28 to KeyEvent.KEYCODE_F8,
            29 to KeyEvent.KEYCODE_F9, 30 to KeyEvent.KEYCODE_F10, 31 to KeyEvent.KEYCODE_F11, 32 to KeyEvent.KEYCODE_F12
        )
        private val SHIFTABLE = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END
        )
    }
}
