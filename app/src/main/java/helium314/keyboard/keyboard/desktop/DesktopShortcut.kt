// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.desktop

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.prefs

data class DesktopShortcut(
    val id: String,
    val title: String,
    val subtitle: String,
    val keyCode: Int,
    val isCtrl: Boolean = true,
    val isShift: Boolean = false,
    val isAlt: Boolean = false,
    val longPressCode: Int = 0,
    val category: String = "Editing"
)

object DesktopShortcutsCatalog {
    const val PREF_DESKTOP_SHORTCUTS_CONFIG = "pref_desktop_shortcuts_config"

    val ALL_SHORTCUTS: List<DesktopShortcut> = listOf(
        // Primary text editing
        DesktopShortcut("SELECT_ALL", "Select All", "Ctrl + A", KeyEvent.KEYCODE_A, isCtrl = true, category = "Editing"),
        DesktopShortcut("COPY", "Copy", "Ctrl + C", KeyEvent.KEYCODE_C, isCtrl = true, longPressCode = KeyCode.PROMPT_LIST, category = "Editing"),
        DesktopShortcut("PASTE", "Paste", "Ctrl + V", KeyEvent.KEYCODE_V, isCtrl = true, longPressCode = KeyCode.CLIPBOARD, category = "Editing"),
        DesktopShortcut("CUT", "Cut", "Ctrl + X", KeyEvent.KEYCODE_X, isCtrl = true, category = "Editing"),
        DesktopShortcut("UNDO", "Undo", "Ctrl + Z", KeyEvent.KEYCODE_Z, isCtrl = true, category = "Editing"),
        DesktopShortcut("REDO", "Redo", "Ctrl + Y", KeyEvent.KEYCODE_Y, isCtrl = true, category = "Editing"),
        DesktopShortcut("SEARCH", "Find", "Ctrl + F", KeyEvent.KEYCODE_F, isCtrl = true, category = "Editing"),
        DesktopShortcut("REPLACE", "Replace", "Ctrl + H", KeyEvent.KEYCODE_H, isCtrl = true, category = "Editing"),
        DesktopShortcut("SELECT_WORD", "Select Word", "Ctrl + W", KeyEvent.KEYCODE_W, isCtrl = true, longPressCode = KeyCode.CLIPBOARD_SELECT_ALL, category = "Editing"),
        DesktopShortcut("SELECT_LINE", "Select Line", "Shift + End", KeyEvent.KEYCODE_MOVE_END, isCtrl = false, isShift = true, category = "Editing"),
        DesktopShortcut("DUPLICATE", "Duplicate", "Ctrl + D", KeyEvent.KEYCODE_D, isCtrl = true, category = "Editing"),
        DesktopShortcut("GO_TO_LINE", "Go to Line", "Ctrl + G", KeyEvent.KEYCODE_G, isCtrl = true, category = "Editing"),

        // Navigation
        DesktopShortcut("TOP", "Top", "Ctrl + Home", KeyEvent.KEYCODE_MOVE_HOME, isCtrl = true, category = "Navigation"),
        DesktopShortcut("BOTTOM", "Bottom", "Ctrl + End", KeyEvent.KEYCODE_MOVE_END, isCtrl = true, category = "Navigation"),
        DesktopShortcut("PAGE_UP", "Page Up", "PgUp", KeyEvent.KEYCODE_PAGE_UP, isCtrl = false, category = "Navigation"),
        DesktopShortcut("PAGE_DOWN", "Page Down", "PgDn", KeyEvent.KEYCODE_PAGE_DOWN, isCtrl = false, category = "Navigation"),
        DesktopShortcut("LINE_START", "Line Start", "Home", KeyEvent.KEYCODE_MOVE_HOME, isCtrl = false, category = "Navigation"),
        DesktopShortcut("LINE_END", "Line End", "End", KeyEvent.KEYCODE_MOVE_END, isCtrl = false, category = "Navigation"),

        // Browser & Application
        DesktopShortcut("SAVE", "Save", "Ctrl + S", KeyEvent.KEYCODE_S, isCtrl = true, category = "Browser"),
        DesktopShortcut("NEW_TAB", "New Tab", "Ctrl + T", KeyEvent.KEYCODE_T, isCtrl = true, category = "Browser"),
        DesktopShortcut("CLOSE_TAB", "Close Tab", "Ctrl + W", KeyEvent.KEYCODE_W, isCtrl = true, category = "Browser"),
        DesktopShortcut("REFRESH", "Refresh", "Ctrl + R", KeyEvent.KEYCODE_R, isCtrl = true, category = "Browser"),
        DesktopShortcut("BOOKMARK", "Bookmark", "Ctrl + D", KeyEvent.KEYCODE_D, isCtrl = true, category = "Browser"),
        DesktopShortcut("PRINT", "Print", "Ctrl + P", KeyEvent.KEYCODE_P, isCtrl = true, category = "Browser"),
        DesktopShortcut("ZOOM_IN", "Zoom In", "Ctrl + =", KeyEvent.KEYCODE_EQUALS, isCtrl = true, category = "Browser"),
        DesktopShortcut("ZOOM_OUT", "Zoom Out", "Ctrl + -", KeyEvent.KEYCODE_MINUS, isCtrl = true, category = "Browser"),
        DesktopShortcut("ESCAPE", "Escape", "Esc", KeyEvent.KEYCODE_ESCAPE, isCtrl = false, category = "Browser"),

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
        return if (list.size >= 7) list.take(7) else {
            val fallback = list.toMutableList()
            for (defId in DEFAULT_CHOSEN_IDS) {
                if (fallback.none { it.id == defId }) {
                    SHORTCUTS_MAP[defId]?.let { fallback.add(it) }
                }
                if (fallback.size == 7) break
            }
            fallback
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
        saved.split(Separators.ENTRY).forEach {
            val id = it.split(Separators.KV)[0]
            SHORTCUTS_MAP[id]?.let { shortcut ->
                orderedList.add(shortcut to (savedMap[id] ?: false))
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
        val serialized = items.joinToString(Separators.ENTRY) { "${it.first.id}${Separators.KV}${it.second}" }
        prefs.edit { putString(PREF_DESKTOP_SHORTCUTS_CONFIG, serialized) }
    }

    fun executeShortcut(
        context: Context,
        shortcut: DesktopShortcut,
        ic: InputConnection?,
        actionListener: KeyboardActionListener?
    ) {
        try {
            if (ic == null) return
            val now = SystemClock.uptimeMillis()
            var meta = 0
            if (shortcut.isCtrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            if (shortcut.isShift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            if (shortcut.isAlt) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, shortcut.keyCode, 0, meta)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, shortcut.keyCode, 0, meta)
            ic.sendKeyEvent(down)
            ic.sendKeyEvent(up)

            // Context action fallback for common editable operations
            when (shortcut.id) {
                "SELECT_ALL" -> ic.performContextMenuAction(android.R.id.selectAll)
                "COPY" -> ic.performContextMenuAction(android.R.id.copy)
                "PASTE" -> ic.performContextMenuAction(android.R.id.paste)
                "CUT" -> ic.performContextMenuAction(android.R.id.cut)
                "UNDO" -> ic.performContextMenuAction(android.R.id.undo)
                "REDO" -> ic.performContextMenuAction(android.R.id.redo)
            }
        } catch (t: Throwable) {
            LogCatcher.log('E', "DesktopShortcuts", "executeShortcut-${shortcut.id}: ${t.message}", t)
        }
    }
}
