package io.github.pons.display

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * An invisible text field that collects what the Android keyboard types and
 * forwards each character to Linux. It stays empty, except for one placeholder
 * character so the keyboard keeps sending Backspace.
 */
class SoftKeyboard(context: Context, private val input: () -> InputBridge?) {

    val view = EditText(context).apply {
        alpha = 0f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setText(PLACEHOLDER)
        setSelection(1)
    }

    private var resetting = false

    init {
        view.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (resetting) return
                val bridge = input() ?: return
                repeat(before) { bridge.type(InputBridge.KEY_BACKSPACE) }
                s.subSequence(start, start + count).codePoints().forEach {
                    bridge.type(InputBridge.keysymFor(it))
                }
            }
            override fun afterTextChanged(s: Editable) {
                if (resetting || s.toString() == PLACEHOLDER) return
                resetting = true
                s.replace(0, s.length, PLACEHOLDER)
                view.setSelection(1)
                resetting = false
            }
        })
        view.setOnEditorActionListener { _, _, _ ->
            input()?.type(InputBridge.KEY_RETURN)
            true
        }
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val sym = when (keyCode) {
                KeyEvent.KEYCODE_DEL -> InputBridge.KEY_BACKSPACE
                KeyEvent.KEYCODE_ENTER -> InputBridge.KEY_RETURN
                KeyEvent.KEYCODE_TAB -> InputBridge.KEY_TAB
                KeyEvent.KEYCODE_ESCAPE -> InputBridge.KEY_ESCAPE
                else -> return@setOnKeyListener false
            }
            input()?.type(sym)
            true
        }
    }

    fun toggle() {
        val imm = view.context.getSystemService(InputMethodManager::class.java)
        if (view.hasFocus()) {
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        } else {
            view.requestFocus()
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private companion object {
        const val PLACEHOLDER = " "
    }
}
