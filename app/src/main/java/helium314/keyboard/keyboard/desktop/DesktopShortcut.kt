// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.desktop

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.utils.LogCatcher

enum class DesktopShortcutKind {
    KEY_EVENT,
    CONTEXT_THEN_KEY,
    KEY_THEN_CONTEXT,
    SELECT_WORD,
    SELECT_LINE,
    NAV_TOP,
    NAV_BOTTOM
}

data class DesktopShortcut(
    val id: String,
    val title: String,
    val subtitle: String,
    val keyCode: Int,
    val isCtrl: Boolean = true,
    val isShift: Boolean = false,
    val isAlt: Boolean = false,
    val longPressCode: Int = 0,
    val category: String = "Editing",
    val dispatchKind: DesktopShortcutKind = DesktopShortcutKind.KEY_EVENT,
    val contextActionId: Int = 0
)

object DesktopShortcutsCatalog {
    const val PREF_DESKTOP_SHORTCUTS_CONFIG = "pref_desktop_shortcuts_config"
    const val MAX_MODAL_SLOTS = 7

    val ALL_SHORTCUTS: List<DesktopShortcut> = listOf(
        // Primary text & code editing
        DesktopShortcut(
            "SELECT_ALL", "Select All", "Ctrl + A", KeyEvent.KEYCODE_A, isCtrl = true,
            category = "Editing", dispatchKind = DesktopShortcutKind.KEY_THEN_CONTEXT, contextActionId = android.R.id.selectAll
        ),
        DesktopShortcut(
            "COPY", "Copy", "Ctrl + C", KeyEvent.KEYCODE_C, isCtrl = true, longPressCode = KeyCode.PROMPT_LIST,
            category = "Editing", dispatchKind = DesktopShortcutKind.CONTEXT_THEN_KEY, contextActionId = android.R.id.copy
        ),
        DesktopShortcut(
            "PASTE", "Paste", "Ctrl + V", KeyEvent.KEYCODE_V, isCtrl = true, longPressCode = KeyCode.CLIPBOARD,
            category = "Editing", dispatchKind = DesktopShortcutKind.CONTEXT_THEN_KEY, contextActionId = android.R.id.paste
        ),
        DesktopShortcut(
            "CUT", "Cut", "Ctrl + X", KeyEvent.KEYCODE_X, isCtrl = true,
            category = "Editing", dispatchKind = DesktopShortcutKind.CONTEXT_THEN_KEY, contextActionId = android.R.id.cut
        ),
        DesktopShortcut(
            "UNDO", "Undo", "Ctrl + Z", KeyEvent.KEYCODE_Z, isCtrl = true,
            category = "Editing", dispatchKind = DesktopShortcutKind.KEY_THEN_CONTEXT, contextActionId = android.R.id.undo
        ),
        DesktopShortcut(
            "REDO", "Redo", "Ctrl + Shift + Z", KeyEvent.KEYCODE_Z, isCtrl = true, isShift = true,
            category = "Editing", dispatchKind = DesktopShortcutKind.KEY_THEN_CONTEXT, contextActionId = android.R.id.redo
        ),
        DesktopShortcut("SEARCH", "Find", "Ctrl + F", KeyEvent.KEYCODE_F, isCtrl = true, category = "Editing"),
        DesktopShortcut("REPLACE", "Replace", "Ctrl + H", KeyEvent.KEYCODE_H, isCtrl = true, category = "Editing"),
        DesktopShortcut(
            "SELECT_WORD", "Select Word", "Select Word", KeyEvent.KEYCODE_W, isCtrl = false,
            longPressCode = KeyCode.CLIPBOARD_SELECT_ALL, category = "Editing", dispatchKind = DesktopShortcutKind.SELECT_WORD
        ),
        DesktopShortcut(
            "SELECT_LINE", "Select Line", "Home → Shift+End", KeyEvent.KEYCODE_MOVE_END, isCtrl = false, isShift = true,
            category = "Editing", dispatchKind = DesktopShortcutKind.SELECT_LINE
        ),
        DesktopShortcut("DUPLICATE", "Duplicate Line", "Shift + Alt + ↓", KeyEvent.KEYCODE_DPAD_DOWN, isCtrl = false, isShift = true, isAlt = true, category = "Editing"),
        DesktopShortcut("TAB", "Tab", "Tab", KeyEvent.KEYCODE_TAB, isCtrl = false, category = "Editing"),
        DesktopShortcut("OUTDENT", "Outdent", "Shift + Tab", KeyEvent.KEYCODE_TAB, isCtrl = false, isShift = true, category = "Editing"),
        DesktopShortcut("COMMENT", "Comment", "Ctrl + /", KeyEvent.KEYCODE_SLASH, isCtrl = true, category = "Editing"),
        DesktopShortcut("SELECT_NEXT", "Select Next", "Ctrl + D", KeyEvent.KEYCODE_D, isCtrl = true, category = "Editing"),
        DesktopShortcut("DELETE_LINE", "Delete Line", "Ctrl + Shift + K", KeyEvent.KEYCODE_K, isCtrl = true, isShift = true, category = "Editing"),
        DesktopShortcut("GO_TO_LINE", "Go to Line", "Ctrl + G", KeyEvent.KEYCODE_G, isCtrl = true, category = "Editing"),
        DesktopShortcut("SAVE", "Save", "Ctrl + S", KeyEvent.KEYCODE_S, isCtrl = true, category = "Editing"),

        // Navigation
        DesktopShortcut(
            "TOP", "Top", "Ctrl + Home", KeyEvent.KEYCODE_MOVE_HOME, isCtrl = true,
            category = "Navigation", dispatchKind = DesktopShortcutKind.NAV_TOP
        ),
        DesktopShortcut(
            "BOTTOM", "Bottom", "Ctrl + End", KeyEvent.KEYCODE_MOVE_END, isCtrl = true,
            category = "Navigation", dispatchKind = DesktopShortcutKind.NAV_BOTTOM
        ),
        DesktopShortcut("PAGE_UP", "Page Up", "PgUp", KeyEvent.KEYCODE_PAGE_UP, isCtrl = false, category = "Navigation"),
        DesktopShortcut("PAGE_DOWN", "Page Down", "PgDn", KeyEvent.KEYCODE_PAGE_DOWN, isCtrl = false, category = "Navigation"),
        DesktopShortcut("LINE_START", "Line Start", "Home", KeyEvent.KEYCODE_MOVE_HOME, isCtrl = false, category = "Navigation"),
        DesktopShortcut("LINE_END", "Line End", "End", KeyEvent.KEYCODE_MOVE_END, isCtrl = false, category = "Navigation"),
        DesktopShortcut("ESCAPE", "Escape", "Esc", KeyEvent.KEYCODE_ESCAPE, isCtrl = false, category = "Navigation"),

        // Formatting
        DesktopShortcut("BOLD", "Bold", "Ctrl + B", KeyEvent.KEYCODE_B, isCtrl = true, category = "Formatting"),
        DesktopShortcut("ITALIC", "Italic", "Ctrl + I", KeyEvent.KEYCODE_I, isCtrl = true, category = "Formatting"),
        DesktopShortcut("UNDERLINE", "Underline", "Ctrl + U", KeyEvent.KEYCODE_U, isCtrl = true, category = "Formatting")
    )

    private val SHORTCUTS_MAP = ALL_SHORTCUTS.associateBy { it.id }

    val DEFAULT_CHOSEN_IDS = listOf(
        "SELECT_ALL", // Row 1 (3 items)
        "COPY",
        "PASTE",
        "UNDO",       // Row 2 (2 items)
        "REDO",
        "SEARCH",     // Row 3 (2 items)
        "SELECT_WORD"
    )

    fun getChosenShortcuts(prefs: SharedPreferences): List<DesktopShortcut> {
        val saved = prefs.getString(PREF_DESKTOP_SHORTCUTS_CONFIG, null)
        if (saved.isNullOrEmpty()) {
            return DEFAULT_CHOSEN_IDS.mapNotNull { SHORTCUTS_MAP[it] }
        }
        val ids = saved.split(Separators.ENTRY).mapNotNull {
            val parts = it.split(Separators.KV)
            if (parts.size == 2 && parts[1].toBoolean()) parts[0] else null
        }
        val list = ids.mapNotNull { SHORTCUTS_MAP[it] }
        return if (list.isEmpty()) {
            DEFAULT_CHOSEN_IDS.mapNotNull { SHORTCUTS_MAP[it] }
        } else {
            list.take(MAX_MODAL_SLOTS)
        }
    }

    fun getAllWithConfig(prefs: SharedPreferences): List<Pair<DesktopShortcut, Boolean>> {
        val saved = prefs.getString(PREF_DESKTOP_SHORTCUTS_CONFIG, null)
        if (saved.isNullOrEmpty()) {
            val chosenSet = DEFAULT_CHOSEN_IDS.toSet()
            return ALL_SHORTCUTS.map { it to chosenSet.contains(it.id) }
        }
        val savedMap = saved.split(Separators.ENTRY).associate {
            val parts = it.split(Separators.KV)
            parts[0] to (parts.getOrNull(1)?.toBoolean() ?: false)
        }
        val orderedList = mutableListOf<Pair<DesktopShortcut, Boolean>>()
        val seen = mutableSetOf<String>()
        var enabledCount = 0
        saved.split(Separators.ENTRY).forEach {
            val id = it.split(Separators.KV)[0]
            SHORTCUTS_MAP[id]?.let { shortcut ->
                val rawEnabled = savedMap[id] ?: false
                val effectiveEnabled = if (rawEnabled && enabledCount < MAX_MODAL_SLOTS) {
                    enabledCount++
                    true
                } else {
                    false
                }
                orderedList.add(shortcut to effectiveEnabled)
                seen.add(id)
            }
        }
        ALL_SHORTCUTS.forEach { shortcut ->
            if (!seen.contains(shortcut.id)) {
                orderedList.add(shortcut to false)
            }
        }
        return orderedList
    }

    fun saveConfig(prefs: SharedPreferences, items: List<Pair<DesktopShortcut, Boolean>>) {
        var enabledCount = 0
        val serialized = items.joinToString(Separators.ENTRY) { (shortcut, enabled) ->
            val effective = if (enabled && enabledCount < MAX_MODAL_SLOTS) {
                enabledCount++
                true
            } else {
                false
            }
            "${shortcut.id}${Separators.KV}$effective"
        }
        prefs.edit { putString(PREF_DESKTOP_SHORTCUTS_CONFIG, serialized) }
    }

    fun buildMetaState(isCtrl: Boolean = false, isShift: Boolean = false, isAlt: Boolean = false): Int {
        var meta = 0
        if (isCtrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (isShift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (isAlt) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        return meta
    }

    fun sendDesktopKeyEvent(
        ic: InputConnection?,
        keyCode: Int,
        metaState: Int = 0,
        repeatCount: Int = 0
    ): Boolean {
        if (ic == null) return false
        val downTime = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        val down = KeyEvent(
            downTime,
            downTime,
            KeyEvent.ACTION_DOWN,
            keyCode,
            repeatCount,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            flags
        )
        val downSent = ic.sendKeyEvent(down)
        val upTime = maxOf(SystemClock.uptimeMillis(), downTime + 1L)
        val up = KeyEvent(
            downTime,
            upTime,
            KeyEvent.ACTION_UP,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            flags
        )
        val upSent = ic.sendKeyEvent(up)
        return downSent && upSent
    }

    fun executeShortcut(
        context: Context,
        shortcut: DesktopShortcut,
        ic: InputConnection?,
        actionListener: KeyboardActionListener?
    ) {
        try {
            val meta = buildMetaState(shortcut.isCtrl, shortcut.isShift, shortcut.isAlt)
            when (shortcut.dispatchKind) {
                DesktopShortcutKind.SELECT_WORD -> {
                    actionListener?.onCodeInput(
                        KeyCode.CLIPBOARD_SELECT_WORD,
                        Constants.NOT_A_COORDINATE,
                        Constants.NOT_A_COORDINATE,
                        false
                    )
                }
                DesktopShortcutKind.SELECT_LINE -> {
                    sendDesktopKeyEvent(ic, KeyEvent.KEYCODE_MOVE_HOME, metaState = 0)
                    sendDesktopKeyEvent(ic, KeyEvent.KEYCODE_MOVE_END, metaState = buildMetaState(isShift = true))
                }
                DesktopShortcutKind.NAV_TOP -> {
                    if (actionListener != null) {
                        actionListener.onCodeInput(
                            KeyCode.MOVE_START_OF_PAGE,
                            Constants.NOT_A_COORDINATE,
                            Constants.NOT_A_COORDINATE,
                            false
                        )
                    } else {
                        sendDesktopKeyEvent(ic, KeyEvent.KEYCODE_MOVE_HOME, metaState = buildMetaState(isCtrl = true))
                    }
                }
                DesktopShortcutKind.NAV_BOTTOM -> {
                    if (actionListener != null) {
                        actionListener.onCodeInput(
                            KeyCode.MOVE_END_OF_PAGE,
                            Constants.NOT_A_COORDINATE,
                            Constants.NOT_A_COORDINATE,
                            false
                        )
                    } else {
                        sendDesktopKeyEvent(ic, KeyEvent.KEYCODE_MOVE_END, metaState = buildMetaState(isCtrl = true))
                    }
                }
                DesktopShortcutKind.CONTEXT_THEN_KEY -> {
                    val handledByContext = ic != null &&
                        shortcut.contextActionId != 0 &&
                        ic.performContextMenuAction(shortcut.contextActionId)
                    if (!handledByContext) {
                        sendDesktopKeyEvent(ic, shortcut.keyCode, meta)
                    }
                }
                DesktopShortcutKind.KEY_THEN_CONTEXT -> {
                    val sent = sendDesktopKeyEvent(ic, shortcut.keyCode, meta)
                    if (!sent && ic != null && shortcut.contextActionId != 0) {
                        ic.performContextMenuAction(shortcut.contextActionId)
                    }
                }
                DesktopShortcutKind.KEY_EVENT -> {
                    sendDesktopKeyEvent(ic, shortcut.keyCode, meta)
                }
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', "DesktopShortcuts", "executeShortcut-${shortcut.id}: ${t.message}", t)
        }
    }
}
