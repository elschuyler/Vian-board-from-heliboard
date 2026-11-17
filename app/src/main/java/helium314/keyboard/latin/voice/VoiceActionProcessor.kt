// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import helium314.keyboard.latin.database.VoiceReplacementDao
import helium314.keyboard.latin.utils.prefs
import java.util.regex.Pattern

/**
 * Deterministic post-processing pipeline for Whisper transcription text.
 * Executes custom Word Improvement rules first (protecting those spans),
 * then applies active slotted Voice Actions in strict priority order:
 * Numbers -> Multiply (2x) -> Star -> Custom Symbols -> Brackets/Wrappers -> Next Line.
 */
object VoiceActionProcessor {

    private val UNITS = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L,
        "five" to 5L, "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L
    )
    private val TEENS = mapOf(
        "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L, "fourteen" to 14L,
        "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L, "eighteen" to 18L, "nineteen" to 19L
    )
    private val TENS = mapOf(
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
        "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L
    )
    private val SCALES = mapOf(
        "hundred" to 100L,
        "thousand" to 1_000L,
        "million" to 1_000_000L
    )

    private val ALL_NUMBER_WORDS: Set<String> = UNITS.keys + TEENS.keys + TENS.keys + SCALES.keys

    // Matches a run of spoken English cardinal words (optionally linked by hyphens or "and")
    private val NUMBER_TOKEN_OR_AND = "(?:${ALL_NUMBER_WORDS.joinToString("|")}|and)"
    private val NUMBER_HEAD = "(?:${(UNITS.keys + TEENS.keys + TENS.keys).joinToString("|")})"
    private val NUMBER_PHRASE_REGEX = Regex(
        "(?i)(?<![\\p{L}\\p{N}_])$NUMBER_HEAD(?:[-\\s]+$NUMBER_TOKEN_OR_AND)*(?![\\p{L}\\p{N}_])"
    )
    private val WORD_TOKEN_REGEX = Regex("[A-Za-z]+")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val TOKEN_SEP = "(?:[,\\.\\-\\s]+)"

    fun processText(context: Context, rawText: String): String {
        if (rawText.isBlank()) return rawText
        val daoResult = VoiceReplacementDao.getInstance(context).applyReplacementsWithMask(rawText)
        val activeActions = VoiceActionCatalog.getActiveSlottedActions(context.prefs())
        return process(
            inputText = daoResult.text,
            activeActions = activeActions,
            initialProtectedMask = daoResult.protectedMask
        )
    }

    fun process(
        inputText: String,
        activeActions: List<VoiceActionItem>,
        initialProtectedMask: BooleanArray = BooleanArray(inputText.length)
    ): String {
        if (inputText.isEmpty() || activeActions.isEmpty()) return inputText

        var state = MutableTextState(
            text = inputText,
            mask = if (initialProtectedMask.size == inputText.length) {
                initialProtectedMask.copyOf()
            } else {
                BooleanArray(inputText.length)
            }
        )

        // 1. Spoken Numbers (leave converted digits unprotected so Multiply can still combine "2 times" -> "2x")
        val numbersEnabled = activeActions.any { it.kind == VoiceActionKind.NUMBERS && it.isEnabled }
        if (numbersEnabled) {
            state = applySpokenNumbers(state)
        }

        // 2. Multiply (2x)
        for (action in activeActions) {
            if (action.kind == VoiceActionKind.MULTIPLY && action.isEnabled) {
                state = applyMultiplyAction(state, action)
            }
        }

        // 3. Star (*)
        for (action in activeActions) {
            if (action.kind == VoiceActionKind.STAR && action.isEnabled) {
                state = applySimpleSymbolAction(state, action)
            }
        }

        // 4. Custom single symbol/text actions (WORD, TIGHT, ATTACH_LEFT)
        for (action in activeActions) {
            if (action.kind == VoiceActionKind.CUSTOM_SYMBOL &&
                action.isEnabled &&
                action.spacing != VoiceActionSpacing.NEWLINE
            ) {
                state = applySimpleSymbolAction(state, action)
            }
        }

        // 5. Brackets & Custom Wrappers
        for (action in activeActions) {
            if ((action.kind == VoiceActionKind.BRACKETS || action.kind == VoiceActionKind.CUSTOM_WRAPPER) &&
                action.isEnabled
            ) {
                state = applyWrapperAction(state, action)
            }
        }

        // 6. Next Line & Newline custom actions
        for (action in activeActions) {
            if ((action.kind == VoiceActionKind.NEXT_LINE ||
                    (action.kind == VoiceActionKind.CUSTOM_SYMBOL && action.spacing == VoiceActionSpacing.NEWLINE)) &&
                action.isEnabled
            ) {
                state = applyNewlineAction(state, action)
            }
        }

        return state.text
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim(' ', '\t')
    }

    private data class MutableTextState(
        val text: String,
        val mask: BooleanArray
    )

    private data class PendingEdit(
        val start: Int,
        val endExclusive: Int,
        val replacement: String,
        val protectReplacement: Boolean
    )

    private fun applyEdits(state: MutableTextState, edits: List<PendingEdit>): MutableTextState {
        if (edits.isEmpty()) return state
        val sorted = edits.sortedBy { it.start }
        val sb = StringBuilder(state.text.length)
        val maskOut = ArrayList<Boolean>(state.text.length)
        var cursor = 0

        for (edit in sorted) {
            if (edit.start < cursor || edit.start > edit.endExclusive || edit.endExclusive > state.text.length) continue
            for (i in cursor until edit.start) {
                sb.append(state.text[i])
                maskOut.add(state.mask[i])
            }
            sb.append(edit.replacement)
            repeat(edit.replacement.length) {
                maskOut.add(edit.protectReplacement)
            }
            cursor = edit.endExclusive
        }
        for (i in cursor until state.text.length) {
            sb.append(state.text[i])
            maskOut.add(state.mask[i])
        }
        return MutableTextState(sb.toString(), maskOut.toBooleanArray())
    }

    private fun isRangeProtected(mask: BooleanArray, start: Int, endExclusive: Int): Boolean {
        for (i in start until endExclusive.coerceAtMost(mask.size)) {
            if (mask[i]) return true
        }
        return false
    }

    /**
     * Converts spoken English cardinal words into digits while respecting place-value rules:
     * - "twenty four" / "twenty-four" -> "24"
     * - "one hundred five" / "one hundred and five" -> "105"
     * - "two four" (unit + unit) -> "2 4" (keeps separate knitting counts distinct)
     * - Trailing "and" (e.g. "one hundred and purl") is left untouched -> "100 and purl"
     */
    internal fun convertSpokenNumbersSegment(segment: String): String? {
        val tokens = WORD_TOKEN_REGEX.findAll(segment).toList()
        if (tokens.isEmpty()) return null

        val parts = mutableListOf<String>()
        var idx = 0
        var total = 0L
        var current = 0L
        var hasActive = false
        var lastKind = NumTokenKind.NONE

        fun flushNumber() {
            if (hasActive) {
                parts.add((total + current).toString())
                total = 0L
                current = 0L
                hasActive = false
                lastKind = NumTokenKind.NONE
            }
        }

        while (idx < tokens.size) {
            val word = tokens[idx].value.lowercase()
            if (word == "and") {
                val nextWord = tokens.getOrNull(idx + 1)?.value?.lowercase()
                val nextVal = nextWord?.let { numberTokenValue(it) }
                val canJoinAnd = hasActive &&
                    (lastKind == NumTokenKind.HUNDRED || lastKind == NumTokenKind.BIG_SCALE) &&
                    nextVal != null && nextVal < 100L
                if (canJoinAnd) {
                    idx++
                    continue
                } else {
                    flushNumber()
                    // Rebuild remaining suffix from original segment offset if trailing "and ..."
                    val remainderStart = tokens[idx].range.first
                    val prefix = parts.joinToString(" ")
                    val suffix = segment.substring(remainderStart)
                    return if (prefix.isEmpty()) suffix else "$prefix $suffix"
                }
            }

            when {
                UNITS.containsKey(word) -> {
                    val v = UNITS.getValue(word)
                    if (!hasActive) {
                        current = v
                        hasActive = true
                        lastKind = NumTokenKind.UNIT
                    } else if (lastKind == NumTokenKind.TENS && v != 0L) {
                        current += v
                        lastKind = NumTokenKind.UNIT
                    } else if ((lastKind == NumTokenKind.HUNDRED || lastKind == NumTokenKind.BIG_SCALE) && v != 0L) {
                        current += v
                        lastKind = NumTokenKind.UNIT
                    } else {
                        flushNumber()
                        current = v
                        hasActive = true
                        lastKind = NumTokenKind.UNIT
                    }
                }
                TEENS.containsKey(word) -> {
                    val v = TEENS.getValue(word)
                    if (!hasActive) {
                        current = v
                        hasActive = true
                        lastKind = NumTokenKind.TEEN
                    } else if (lastKind == NumTokenKind.HUNDRED || lastKind == NumTokenKind.BIG_SCALE) {
                        current += v
                        lastKind = NumTokenKind.TEEN
                    } else {
                        flushNumber()
                        current = v
                        hasActive = true
                        lastKind = NumTokenKind.TEEN
                    }
                }
                TENS.containsKey(word) -> {
                    val v = TENS.getValue(word)
                    if (!hasActive) {
                        current = v
                        hasActive = true
                        lastKind = NumTokenKind.TENS
                    } else if (lastKind == NumTokenKind.HUNDRED || lastKind == NumTokenKind.BIG_SCALE) {
                        current += v
                        lastKind = NumTokenKind.TENS
                    } else {
                        flushNumber()
                        current = v
                        hasActive = true
                        lastKind = NumTokenKind.TENS
                    }
                }
                word == "hundred" -> {
                    if (hasActive && current in 1L..99L && lastKind != NumTokenKind.HUNDRED) {
                        current *= 100L
                        lastKind = NumTokenKind.HUNDRED
                    } else {
                        flushNumber()
                        parts.add(tokens[idx].value)
                    }
                }
                word == "thousand" || word == "million" -> {
                    val scale = SCALES.getValue(word)
                    if (hasActive && current > 0L) {
                        total += current * scale
                        current = 0L
                        lastKind = NumTokenKind.BIG_SCALE
                    } else {
                        flushNumber()
                        parts.add(tokens[idx].value)
                    }
                }
                else -> {
                    flushNumber()
                    parts.add(tokens[idx].value)
                }
            }
            idx++
        }
        flushNumber()
        return parts.joinToString(" ")
    }

    private enum class NumTokenKind { NONE, UNIT, TEEN, TENS, HUNDRED, BIG_SCALE }

    private fun numberTokenValue(word: String): Long? =
        UNITS[word] ?: TEENS[word] ?: TENS[word] ?: SCALES[word]

    private fun applySpokenNumbers(state: MutableTextState): MutableTextState {
        val edits = mutableListOf<PendingEdit>()
        for (match in NUMBER_PHRASE_REGEX.findAll(state.text)) {
            val start = match.range.first
            val endExclusive = match.range.last + 1
            if (isRangeProtected(state.mask, start, endExclusive)) continue
            val converted = convertSpokenNumbersSegment(match.value) ?: continue
            if (converted != match.value) {
                // Keep protectReplacement=false so Multiply ("2 times" -> "2x") can immediately combine
                edits.add(PendingEdit(start, endExclusive, converted, protectReplacement = false))
            }
        }
        return applyEdits(state, edits)
    }

    private fun applyMultiplyAction(state: MutableTextState, action: VoiceActionItem): MutableTextState {
        val triggers = action.parsedOpenTriggers().sortedByDescending { it.length }
        if (triggers.isEmpty()) return state
        val suffix = action.outputText.ifEmpty { "x" }
        var current = state

        for (trigger in triggers) {
            val triggerPattern = buildTriggerPattern(trigger)
            // Support digits ("2 times") AND spoken number words ("two times", "twenty four times") even if 123 is OFF
            val regex = Regex(
                "(?i)(?<![\\p{L}\\p{N}_])(\\d+|$NUMBER_HEAD(?:[-\\s]+$NUMBER_TOKEN_OR_AND)*)\\s+$triggerPattern(?![\\p{L}\\p{N}_])"
            )
            val edits = mutableListOf<PendingEdit>()
            for (match in regex.findAll(current.text)) {
                val start = match.range.first
                val endExclusive = match.range.last + 1
                if (isRangeProtected(current.mask, start, endExclusive)) continue
                val rawQty = match.groupValues[1]
                val numericQty = if (rawQty.all { it.isDigit() }) {
                    rawQty
                } else {
                    convertSpokenNumbersSegment(rawQty) ?: continue
                }
                // Only apply when the trailing part resolved to pure digits (not e.g. "two and")
                val tokens = numericQty.split(' ')
                val lastDigits = tokens.lastOrNull() ?: continue
                if (!lastDigits.all { it.isDigit() }) continue
                val replacement = if (tokens.size == 1) {
                    "$lastDigits$suffix"
                } else {
                    "${tokens.dropLast(1).joinToString(" ")} $lastDigits$suffix"
                }
                edits.add(PendingEdit(start, endExclusive, replacement, protectReplacement = true))
            }
            current = applyEdits(current, edits)
        }
        return current
    }

    private fun applySimpleSymbolAction(state: MutableTextState, action: VoiceActionItem): MutableTextState {
        val triggers = action.parsedOpenTriggers().sortedByDescending { it.length }
        if (triggers.isEmpty()) return state
        val output = action.outputText
        var current = state

        for (trigger in triggers) {
            val triggerPattern = buildTriggerPattern(trigger)
            val regex = when (action.spacing) {
                VoiceActionSpacing.TIGHT -> Regex("(?i)[ \\t]*(?<![\\p{L}\\p{N}_])$triggerPattern(?![\\p{L}\\p{N}_])[ \\t]*")
                VoiceActionSpacing.ATTACH_LEFT -> Regex("(?i)[ \\t]*(?<![\\p{L}\\p{N}_])$triggerPattern(?![\\p{L}\\p{N}_])")
                else -> Regex("(?i)(?<![\\p{L}\\p{N}_])$triggerPattern(?![\\p{L}\\p{N}_])")
            }
            val edits = mutableListOf<PendingEdit>()
            for (match in regex.findAll(current.text)) {
                val start = match.range.first
                val endExclusive = match.range.last + 1
                if (isRangeProtected(current.mask, start, endExclusive)) continue
                edits.add(PendingEdit(start, endExclusive, output, protectReplacement = true))
            }
            current = applyEdits(current, edits)
        }
        return current
    }

    private fun applyWrapperAction(state: MutableTextState, action: VoiceActionItem): MutableTextState {
        val openTriggers = action.parsedOpenTriggers().sortedByDescending { it.length }
        val closeTriggers = action.parsedCloseTriggers().sortedByDescending { it.length }
        val openOut = action.outputText.ifEmpty { "(" }
        val closeOut = action.closeOutputText.ifEmpty { ")" }
        var current = state

        for (trigger in openTriggers) {
            val triggerPattern = buildTriggerPattern(trigger)
            val regex = Regex("(?i)(?<![\\p{L}\\p{N}_])$triggerPattern(?![\\p{L}\\p{N}_])(?:[ \\t]*,[ \\t]*|[ \\t]*)")
            val edits = mutableListOf<PendingEdit>()
            for (match in regex.findAll(current.text)) {
                val start = match.range.first
                val endExclusive = match.range.last + 1
                if (isRangeProtected(current.mask, start, endExclusive)) continue
                edits.add(PendingEdit(start, endExclusive, openOut, protectReplacement = true))
            }
            current = applyEdits(current, edits)
        }

        for (trigger in closeTriggers) {
            val triggerPattern = buildTriggerPattern(trigger)
            val regex = Regex("(?i)(?:[ \\t]*,[ \\t]*|[ \\t]*)(?<![\\p{L}\\p{N}_])$triggerPattern(?![\\p{L}\\p{N}_])")
            val edits = mutableListOf<PendingEdit>()
            for (match in regex.findAll(current.text)) {
                val start = match.range.first
                val endExclusive = match.range.last + 1
                if (isRangeProtected(current.mask, start, endExclusive)) continue
                edits.add(PendingEdit(start, endExclusive, closeOut, protectReplacement = true))
            }
            current = applyEdits(current, edits)
        }

        return current
    }

    private fun applyNewlineAction(state: MutableTextState, action: VoiceActionItem): MutableTextState {
        val triggers = action.parsedOpenTriggers().sortedByDescending { it.length }
        if (triggers.isEmpty()) return state
        val newlineOut = action.outputText.ifEmpty { "\n" }
        var current = state

        for (trigger in triggers) {
            val triggerPattern = buildTriggerPattern(trigger)
            // Eat speech-pause commas/periods and surrounding spaces hugging the command
            val regex = Regex(
                "(?i)(?:[ \\t]*[,.])?[ \\t]*(?<![\\p{L}\\p{N}_])$triggerPattern(?![\\p{L}\\p{N}_])(?:[ \\t]*[,.])?[ \\t]*"
            )
            val edits = mutableListOf<PendingEdit>()
            for (match in regex.findAll(current.text)) {
                val start = match.range.first
                val endExclusive = match.range.last + 1
                if (isRangeProtected(current.mask, start, endExclusive)) continue
                edits.add(PendingEdit(start, endExclusive, newlineOut, protectReplacement = true))
            }
            current = applyEdits(current, edits)
        }
        return current
    }

    private fun buildTriggerPattern(phrase: String): String {
        val tokens = phrase.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        return if (tokens.size > 1) {
            tokens.joinToString(TOKEN_SEP) { Pattern.quote(it) }
        } else {
            Pattern.quote(phrase.trim())
        }
    }
}
