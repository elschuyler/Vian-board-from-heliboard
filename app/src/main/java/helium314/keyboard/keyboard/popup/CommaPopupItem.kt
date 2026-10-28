// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.popup

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators

data class CommaPopupItem(
    val id: String,
    val title: String,
    val florisSpec: String,
    val iconRes: Int,
    val isAlwaysEnabled: Boolean = false
)

object CommaPopupsCatalog {
    const val PREF_COMMA_POPUPS_CONFIG = "pref_comma_popups_config"

    const val ID_SETTINGS = "settings"
    const val ID_LOG_KEEPER = "log_keeper"
    const val ID_VOICE = "voice_input"
    const val ID_ONE_HANDED = "one_handed"
    const val ID_PRIVACY_VAULT = "privacy_vault"
    const val ID_EMOJI = "emoji"
    const val ID_DESKTOP_SHORTCUTS = "desktop_shortcuts"

    const val ID_SECURITY_VAULT = "security_vault"
    const val ID_CLIPBOARD = "clipboard"
    const val ID_NUMPAD = "numpad"
    const val ID_DPAD = "dpad"
    const val ID_UNDO = "undo"
    const val ID_REDO = "redo"
    const val ID_SELECT_ALL = "select_all"
    const val ID_SELECT_WORD = "select_word"
    const val ID_COPY = "copy"
    const val ID_CUT = "cut"
    const val ID_PASTE = "paste"
    const val ID_INCOGNITO = "incognito"
    const val ID_AUTOCORRECT = "autocorrect"
    const val ID_CLEAR_CLIPBOARD = "clear_clipboard"
    const val ID_SPLIT = "split"
    const val ID_FLOATING = "floating"
    const val ID_LEFT = "left"
    const val ID_RIGHT = "right"
    const val ID_UP = "up"
    const val ID_DOWN = "down"
    const val ID_WORD_LEFT = "word_left"
    const val ID_WORD_RIGHT = "word_right"
    const val ID_PAGE_START = "page_start"
    const val ID_PAGE_END = "page_end"

    val ALL_ITEMS: List<CommaPopupItem> = listOf(
        // Factory defaults (7 original items)
        CommaPopupItem(
            id = ID_SETTINGS,
            title = "Settings",
            florisSpec = "!icon/settings_key|!code/key_settings",
            iconRes = R.drawable.sym_keyboard_settings_lxx,
            isAlwaysEnabled = true
        ),
        CommaPopupItem(
            id = ID_LOG_KEEPER,
            title = "Log Keeper",
            florisSpec = "!icon/log_keeper_key|!code/key_log_keeper",
            iconRes = R.drawable.ic_settings_about
        ),
        CommaPopupItem(
            id = ID_VOICE,
            title = "Voice Input",
            florisSpec = "!icon/shortcut_key|!code/key_voice_input",
            iconRes = R.drawable.sym_keyboard_voice_lxx
        ),
        CommaPopupItem(
            id = ID_ONE_HANDED,
            title = "One-Handed Mode",
            florisSpec = "!icon/start_onehanded_mode_key|!code/key_toggle_onehanded",
            iconRes = R.drawable.sym_keyboard_start_onehanded_lxx
        ),
        CommaPopupItem(
            id = ID_PRIVACY_VAULT,
            title = "Privacy Vault",
            florisSpec = "!icon/privacy_vault_key|!code/key_privacy_vault",
            iconRes = R.drawable.ic_settings_security
        ),
        CommaPopupItem(
            id = ID_EMOJI,
            title = "Emoji",
            florisSpec = "!icon/emoji_normal_key|!code/key_emoji",
            iconRes = R.drawable.sym_keyboard_smiley_lxx
        ),
        CommaPopupItem(
            id = ID_DESKTOP_SHORTCUTS,
            title = "Desktop Shortcuts",
            florisSpec = "!icon/desktop_shortcuts_key|!code/key_desktop_shortcuts",
            iconRes = R.drawable.ic_settings_toolbar
        ),

        // Extended toolbar tools
        CommaPopupItem(
            id = ID_SECURITY_VAULT,
            title = "Security Vault",
            florisSpec = "!icon/security_vault|!code/${KeyCode.SECURITY_VAULT}",
            iconRes = R.drawable.ic_vpn_key
        ),
        CommaPopupItem(
            id = ID_CLIPBOARD,
            title = "Clipboard History",
            florisSpec = "!icon/clipboard|!code/${KeyCode.CLIPBOARD}",
            iconRes = R.drawable.sym_keyboard_clipboard_lxx
        ),
        CommaPopupItem(
            id = ID_NUMPAD,
            title = "Numpad",
            florisSpec = "!icon/numpad|!code/${KeyCode.NUMPAD}",
            iconRes = R.drawable.sym_keyboard_numpad_key_lxx
        ),
        CommaPopupItem(
            id = ID_DPAD,
            title = "Arrow Keys (DPAD)",
            florisSpec = "!icon/dpad|!code/${KeyCode.DPAD}",
            iconRes = R.drawable.ic_dpad
        ),
        CommaPopupItem(
            id = ID_UNDO,
            title = "Undo",
            florisSpec = "!icon/undo|!code/${KeyCode.UNDO}",
            iconRes = R.drawable.ic_undo
        ),
        CommaPopupItem(
            id = ID_REDO,
            title = "Redo",
            florisSpec = "!icon/redo|!code/${KeyCode.REDO}",
            iconRes = R.drawable.ic_redo
        ),
        CommaPopupItem(
            id = ID_COPY,
            title = "Copy",
            florisSpec = "!icon/copy|!code/${KeyCode.CLIPBOARD_COPY}",
            iconRes = R.drawable.sym_keyboard_copy
        ),
        CommaPopupItem(
            id = ID_CUT,
            title = "Cut",
            florisSpec = "!icon/cut|!code/${KeyCode.CLIPBOARD_CUT}",
            iconRes = R.drawable.sym_keyboard_cut
        ),
        CommaPopupItem(
            id = ID_PASTE,
            title = "Paste",
            florisSpec = "!icon/paste|!code/${KeyCode.CLIPBOARD_PASTE}",
            iconRes = R.drawable.sym_keyboard_paste
        ),
        CommaPopupItem(
            id = ID_SELECT_ALL,
            title = "Select All",
            florisSpec = "!icon/select_all|!code/${KeyCode.CLIPBOARD_SELECT_ALL}",
            iconRes = R.drawable.ic_select_all
        ),
        CommaPopupItem(
            id = ID_SELECT_WORD,
            title = "Select Word",
            florisSpec = "!icon/select_word|!code/${KeyCode.CLIPBOARD_SELECT_WORD}",
            iconRes = R.drawable.ic_select
        ),
        CommaPopupItem(
            id = ID_INCOGNITO,
            title = "Incognito Mode",
            florisSpec = "!icon/incognito|!code/${KeyCode.TOGGLE_INCOGNITO_MODE}",
            iconRes = R.drawable.sym_keyboard_incognito_lxx
        ),
        CommaPopupItem(
            id = ID_AUTOCORRECT,
            title = "Auto-Correction",
            florisSpec = "!icon/autocorrect|!code/${KeyCode.TOGGLE_AUTOCORRECT}",
            iconRes = R.drawable.ic_autocorrect
        ),
        CommaPopupItem(
            id = ID_CLEAR_CLIPBOARD,
            title = "Clear Clipboard",
            florisSpec = "!icon/clear_clipboard|!code/${KeyCode.CLIPBOARD_CLEAR_HISTORY}",
            iconRes = R.drawable.sym_keyboard_clear_clipboard_lxx
        ),
        CommaPopupItem(
            id = ID_SPLIT,
            title = "Split Keyboard",
            florisSpec = "!icon/split|!code/${KeyCode.SPLIT_LAYOUT}",
            iconRes = R.drawable.ic_ime_switcher
        ),
        CommaPopupItem(
            id = ID_FLOATING,
            title = "Floating Keyboard",
            florisSpec = "!icon/floating|!code/${KeyCode.TOGGLE_FLOATING_WINDOW}",
            iconRes = R.drawable.ic_drag_indicator
        ),
        CommaPopupItem(
            id = ID_LEFT,
            title = "Cursor Left",
            florisSpec = "!icon/left|!code/${KeyCode.ARROW_LEFT}",
            iconRes = R.drawable.ic_dpad_left
        ),
        CommaPopupItem(
            id = ID_RIGHT,
            title = "Cursor Right",
            florisSpec = "!icon/right|!code/${KeyCode.ARROW_RIGHT}",
            iconRes = R.drawable.ic_dpad_right
        ),
        CommaPopupItem(
            id = ID_UP,
            title = "Cursor Up",
            florisSpec = "!icon/up|!code/${KeyCode.ARROW_UP}",
            iconRes = R.drawable.ic_dpad_up
        ),
        CommaPopupItem(
            id = ID_DOWN,
            title = "Cursor Down",
            florisSpec = "!icon/down|!code/${KeyCode.ARROW_DOWN}",
            iconRes = R.drawable.ic_dpad_down
        ),
        CommaPopupItem(
            id = ID_WORD_LEFT,
            title = "Word Left",
            florisSpec = "!icon/word_left|!code/${KeyCode.WORD_LEFT}",
            iconRes = R.drawable.ic_word_left
        ),
        CommaPopupItem(
            id = ID_WORD_RIGHT,
            title = "Word Right",
            florisSpec = "!icon/word_right|!code/${KeyCode.WORD_RIGHT}",
            iconRes = R.drawable.ic_word_right
        ),
        CommaPopupItem(
            id = ID_PAGE_START,
            title = "Start of Line",
            florisSpec = "!icon/page_start|!code/${KeyCode.MOVE_START_OF_LINE}",
            iconRes = R.drawable.ic_page_start
        ),
        CommaPopupItem(
            id = ID_PAGE_END,
            title = "End of Line",
            florisSpec = "!icon/page_end|!code/${KeyCode.MOVE_END_OF_LINE}",
            iconRes = R.drawable.ic_page_end
        )
    )

    private val ITEMS_MAP = ALL_ITEMS.associateBy { it.id }

    val DEFAULT_IDS: List<String> = listOf(
        ID_SETTINGS,
        ID_LOG_KEEPER,
        ID_VOICE,
        ID_ONE_HANDED,
        ID_PRIVACY_VAULT,
        ID_EMOJI,
        ID_DESKTOP_SHORTCUTS
    )

    fun getEnabledItems(prefs: SharedPreferences?): List<CommaPopupItem> {
        if (prefs == null) return ALL_ITEMS.filter { it.id in DEFAULT_IDS }
        val saved = prefs.getString(PREF_COMMA_POPUPS_CONFIG, null)
        if (saved.isNullOrEmpty()) {
            return ALL_ITEMS.filter { it.id in DEFAULT_IDS }
        }
        val ids = saved.split(Separators.ENTRY).mapNotNull {
            val parts = it.split(Separators.KV)
            if (parts.size == 2 && parts[1].toBoolean()) parts[0] else null
        }
        val list = ids.mapNotNull { ITEMS_MAP[it] }
        return if (list.any { it.id == ID_SETTINGS }) list else {
            val withSettings = list.toMutableList()
            ITEMS_MAP[ID_SETTINGS]?.let { withSettings.add(0, it) }
            withSettings
        }
    }

    fun getAllWithConfig(prefs: SharedPreferences): List<Pair<CommaPopupItem, Boolean>> {
        val saved = prefs.getString(PREF_COMMA_POPUPS_CONFIG, null)
        if (saved.isNullOrEmpty()) {
            return ALL_ITEMS.map { it to (it.id in DEFAULT_IDS) }
        }
        val savedMap = saved.split(Separators.ENTRY).associate {
            val parts = it.split(Separators.KV)
            parts[0] to (parts.getOrNull(1)?.toBoolean() ?: true)
        }
        val orderedList = mutableListOf<Pair<CommaPopupItem, Boolean>>()
        val seen = mutableSetOf<String>()
        saved.split(Separators.ENTRY).forEach {
            val id = it.split(Separators.KV)[0]
            ITEMS_MAP[id]?.let { item ->
                val enabled = if (item.isAlwaysEnabled) true else (savedMap[id] ?: (item.id in DEFAULT_IDS))
                orderedList.add(item to enabled)
                seen.add(id)
            }
        }
        ALL_ITEMS.forEach { item ->
            if (!seen.contains(item.id)) {
                orderedList.add(item to (item.id in DEFAULT_IDS))
            }
        }
        return orderedList
    }

    fun saveConfig(prefs: SharedPreferences, items: List<Pair<CommaPopupItem, Boolean>>) {
        val serialized = items.joinToString(Separators.ENTRY) { "${it.first.id}${Separators.KV}${if (it.first.isAlwaysEnabled) true else it.second}" }
        prefs.edit { putString(PREF_COMMA_POPUPS_CONFIG, serialized) }
    }
}
