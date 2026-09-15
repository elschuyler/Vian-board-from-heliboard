// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.popup

import android.content.SharedPreferences
import androidx.core.content.edit
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

    val ALL_ITEMS: List<CommaPopupItem> = listOf(
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
        if (prefs == null) return ALL_ITEMS
        val saved = prefs.getString(PREF_COMMA_POPUPS_CONFIG, null)
        if (saved.isNullOrEmpty()) {
            return ALL_ITEMS
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
            return ALL_ITEMS.map { it to true }
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
                val enabled = if (item.isAlwaysEnabled) true else (savedMap[id] ?: true)
                orderedList.add(item to enabled)
                seen.add(id)
            }
        }
        ALL_ITEMS.forEach { item ->
            if (!seen.contains(item.id)) {
                orderedList.add(item to true)
            }
        }
        return orderedList
    }

    fun saveConfig(prefs: SharedPreferences, items: List<Pair<CommaPopupItem, Boolean>>) {
        val serialized = items.joinToString(Separators.ENTRY) { "${it.first.id}${Separators.KV}${if (it.first.isAlwaysEnabled) true else it.second}" }
        prefs.edit { putString(PREF_COMMA_POPUPS_CONFIG, serialized) }
    }
}
