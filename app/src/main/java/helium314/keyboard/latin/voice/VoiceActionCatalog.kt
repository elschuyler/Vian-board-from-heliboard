// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LogCatcher
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class VoiceActionKind {
    NEXT_LINE,
    BRACKETS,
    STAR,
    MULTIPLY,
    NUMBERS,
    CUSTOM_SYMBOL,
    CUSTOM_WRAPPER;

    companion object {
        fun fromString(raw: String?): VoiceActionKind =
            entries.firstOrNull { it.name == raw } ?: CUSTOM_SYMBOL
    }
}

enum class VoiceActionSpacing(val label: String) {
    WORD("Keep spaces"),
    TIGHT("No spaces (e.g. 2/4)"),
    ATTACH_LEFT("Attach left (e.g. row: 1)"),
    NEWLINE("New line");

    companion object {
        fun fromString(raw: String?): VoiceActionSpacing =
            entries.firstOrNull { it.name == raw } ?: WORD
    }
}

data class VoiceActionItem(
    val id: String,
    val title: String,
    val symbol: String,
    val kind: VoiceActionKind,
    val triggerPhrases: String = "",
    val closeTriggerPhrases: String = "",
    val outputText: String = "",
    val closeOutputText: String = "",
    val spacing: VoiceActionSpacing = VoiceActionSpacing.WORD,
    val isEnabled: Boolean = true,
    val isSlotted: Boolean = true
) {
    val isBuiltIn: Boolean
        get() = id in VoiceActionCatalog.BUILTIN_IDS

    fun parsedOpenTriggers(): List<String> = parseTriggerCsv(triggerPhrases)

    fun parsedCloseTriggers(): List<String> = parseTriggerCsv(closeTriggerPhrases)

    companion object {
        fun parseTriggerCsv(raw: String): List<String> =
            raw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
    }
}

/**
 * Desktop-Shortcuts-style catalog and 5-slot modal configuration for Voice Actions,
 * backed by SharedPreferences JSON with full CRUD for user-created action choices.
 */
object VoiceActionCatalog {
    private const val TAG = "VoiceActionCatalog"
    const val MAX_MODAL_SLOTS = 5

    const val ID_NEXT_LINE = "BUILTIN_NEXT_LINE"
    const val ID_BRACKETS = "BUILTIN_BRACKETS"
    const val ID_STAR = "BUILTIN_STAR"
    const val ID_MULTIPLY = "BUILTIN_MULTIPLY"
    const val ID_NUMBERS = "BUILTIN_NUMBERS"

    val BUILTIN_IDS = setOf(
        ID_NEXT_LINE,
        ID_BRACKETS,
        ID_STAR,
        ID_MULTIPLY,
        ID_NUMBERS
    )

    fun defaultItems(): List<VoiceActionItem> = listOf(
        VoiceActionItem(
            id = ID_NEXT_LINE,
            title = "Next Line",
            symbol = "↵",
            kind = VoiceActionKind.NEXT_LINE,
            triggerPhrases = "next line, new line",
            outputText = "\n",
            spacing = VoiceActionSpacing.NEWLINE,
            isEnabled = true,
            isSlotted = true
        ),
        VoiceActionItem(
            id = ID_BRACKETS,
            title = "Brackets",
            symbol = "( )",
            kind = VoiceActionKind.BRACKETS,
            triggerPhrases = "open bracket, open parenthesis",
            closeTriggerPhrases = "close bracket, close parenthesis",
            outputText = "(",
            closeOutputText = ")",
            spacing = VoiceActionSpacing.WORD,
            isEnabled = true,
            isSlotted = true
        ),
        VoiceActionItem(
            id = ID_STAR,
            title = "Star",
            symbol = "*",
            kind = VoiceActionKind.STAR,
            triggerPhrases = "star",
            outputText = "*",
            spacing = VoiceActionSpacing.WORD,
            isEnabled = false,
            isSlotted = true
        ),
        VoiceActionItem(
            id = ID_MULTIPLY,
            title = "Multiply (2x)",
            symbol = "×",
            kind = VoiceActionKind.MULTIPLY,
            triggerPhrases = "times",
            outputText = "x",
            spacing = VoiceActionSpacing.WORD,
            isEnabled = true,
            isSlotted = true
        ),
        VoiceActionItem(
            id = ID_NUMBERS,
            title = "Spoken Numbers",
            symbol = "123",
            kind = VoiceActionKind.NUMBERS,
            triggerPhrases = "",
            outputText = "",
            spacing = VoiceActionSpacing.WORD,
            isEnabled = true,
            isSlotted = true
        )
    )

    fun getAll(prefs: SharedPreferences): List<VoiceActionItem> {
        val raw = prefs.getString(Settings.PREF_VOICE_ACTIONS_CONFIG, null)
        if (raw.isNullOrBlank()) {
            return defaultItems()
        }
        return parseJson(raw)
    }

    fun getSlottedActions(prefs: SharedPreferences): List<VoiceActionItem> =
        getAll(prefs).filter { it.isSlotted }.take(MAX_MODAL_SLOTS)

    fun getActiveSlottedActions(prefs: SharedPreferences): List<VoiceActionItem> =
        getSlottedActions(prefs).filter { it.isEnabled }

    fun saveAll(prefs: SharedPreferences, items: List<VoiceActionItem>) {
        val normalized = normalizeSlots(items)
        val json = serializeToJson(normalized)
        prefs.edit {
            putString(Settings.PREF_VOICE_ACTIONS_CONFIG, json)
        }
    }

    fun toggleActionEnabled(prefs: SharedPreferences, actionId: String): Boolean {
        val list = getAll(prefs).toMutableList()
        val idx = list.indexOfFirst { it.id == actionId }
        if (idx == -1) return false
        val newEnabled = !list[idx].isEnabled
        list[idx] = list[idx].copy(isEnabled = newEnabled)
        saveAll(prefs, list)
        return newEnabled
    }

    fun resetDefaults(prefs: SharedPreferences) {
        saveAll(prefs, defaultItems())
    }

    fun createCustomId(): String = "CUSTOM_${UUID.randomUUID().toString().take(8)}"

    fun normalizeSlots(items: List<VoiceActionItem>): List<VoiceActionItem> {
        val defaultsById = defaultItems().associateBy { it.id }
        val deduped = mutableListOf<VoiceActionItem>()
        val seenIds = mutableSetOf<String>()

        for (item in items) {
            if (item.id.isBlank() || !seenIds.add(item.id)) continue
            deduped.add(item)
        }

        // Ensure all 5 built-in engines remain available in the catalog
        for ((id, def) in defaultsById) {
            if (!seenIds.contains(id)) {
                val activeSlots = deduped.count { it.isSlotted }
                deduped.add(def.copy(isSlotted = activeSlots < MAX_MODAL_SLOTS && def.isSlotted))
                seenIds.add(id)
            }
        }

        // Enforce strict MAX_MODAL_SLOTS cap
        var slotsUsed = 0
        return deduped.map { item ->
            if (item.isSlotted) {
                if (slotsUsed < MAX_MODAL_SLOTS) {
                    slotsUsed++
                    item
                } else {
                    item.copy(isSlotted = false)
                }
            } else {
                item
            }
        }
    }

    internal fun parseJson(raw: String): List<VoiceActionItem> {
        return try {
            val array = JSONArray(raw)
            val parsed = mutableListOf<VoiceActionItem>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                if (id.isEmpty()) continue
                val kind = VoiceActionKind.fromString(obj.optString("kind", ""))
                val defaultMatch = defaultItems().firstOrNull { it.id == id }
                parsed.add(
                    VoiceActionItem(
                        id = id,
                        title = obj.optString("title", defaultMatch?.title ?: id).ifBlank { defaultMatch?.title ?: id },
                        symbol = obj.optString("symbol", defaultMatch?.symbol ?: "•").ifBlank { defaultMatch?.symbol ?: "•" },
                        kind = defaultMatch?.kind ?: kind,
                        triggerPhrases = obj.optString("triggerPhrases", defaultMatch?.triggerPhrases ?: ""),
                        closeTriggerPhrases = obj.optString("closeTriggerPhrases", defaultMatch?.closeTriggerPhrases ?: ""),
                        outputText = obj.optString("outputText", defaultMatch?.outputText ?: ""),
                        closeOutputText = obj.optString("closeOutputText", defaultMatch?.closeOutputText ?: ""),
                        spacing = VoiceActionSpacing.fromString(
                            obj.optString("spacing", defaultMatch?.spacing?.name ?: VoiceActionSpacing.WORD.name)
                        ),
                        isEnabled = obj.optBoolean("isEnabled", defaultMatch?.isEnabled ?: true),
                        isSlotted = obj.optBoolean("isSlotted", defaultMatch?.isSlotted ?: false)
                    )
                )
            }
            if (parsed.isEmpty()) defaultItems() else normalizeSlots(parsed)
        } catch (t: Throwable) {
            LogCatcher.w(TAG, "Failed to parse voice actions config; falling back to defaults: ${t.message}")
            defaultItems()
        }
    }

    internal fun serializeToJson(items: List<VoiceActionItem>): String {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("symbol", item.symbol)
                put("kind", item.kind.name)
                put("triggerPhrases", item.triggerPhrases)
                put("closeTriggerPhrases", item.closeTriggerPhrases)
                put("outputText", item.outputText)
                put("closeOutputText", item.closeOutputText)
                put("spacing", item.spacing.name)
                put("isEnabled", item.isEnabled)
                put("isSlotted", item.isSlotted)
            }
            array.put(obj)
        }
        return array.toString()
    }
}
