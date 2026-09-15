// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.backup

import androidx.annotation.StringRes
import helium314.keyboard.latin.R

/**
 * Modular data categories for granular backup and selective restoration.
 */
enum class BackupModule(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val isSensitive: Boolean = false
) {
    SETTINGS(
        key = "settings",
        titleRes = R.string.backup_module_settings,
        descriptionRes = R.string.backup_module_settings_desc
    ),
    USER_DICTIONARIES(
        key = "dictionaries",
        titleRes = R.string.backup_module_dictionaries,
        descriptionRes = R.string.backup_module_dictionaries_desc
    ),
    CLIPBOARD_PROMPTS(
        key = "clipboard_prompts",
        titleRes = R.string.backup_module_clipboard_prompts,
        descriptionRes = R.string.backup_module_clipboard_prompts_desc
    ),
    WORD_IMPROVEMENT(
        key = "word_improvement",
        titleRes = R.string.backup_module_word_improvement,
        descriptionRes = R.string.backup_module_word_improvement_desc
    ),
    SECURITY_VAULT(
        key = "security_vault",
        titleRes = R.string.backup_module_security_vault,
        descriptionRes = R.string.backup_module_security_vault_desc,
        isSensitive = true
    );

    companion object {
        fun fromKey(key: String): BackupModule? = entries.firstOrNull { it.key == key }
    }
}
