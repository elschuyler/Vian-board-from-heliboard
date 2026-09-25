package helium314.keyboard.latin.suggestions

import android.content.Context
import android.database.Cursor
import android.provider.UserDictionary
import helium314.keyboard.latin.utils.Log

object NumberSuggestionsHelper {
    private const val TAG = "NumberSuggestionsHelper"
    private val DEFAULT_SUFFIXES = listOf("/-", "kg")
    private val KNOWN_UNITS = setOf(
        "/-", "kg", "gm", "g", "km", "m", "cm", "mm", "ltr", "ml", "rs", "₹", "%", "pcs", "box", "pkt", "doz", "oz", "lb"
    )

    private var cachedUserSuffixes: List<String> = emptyList()
    private var lastFetchTimestamp: Long = 0L
    private const val CACHE_TTL_MS = 5000L

    /**
     * Checks if a string represents a purely numeric sequence (digits with optional . or , separators).
     */
    fun isNumeric(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        var hasDigit = false
        for (i in 0 until text.length) {
            val ch = text[i]
            if (ch.isDigit()) {
                hasDigit = true
            } else if (ch != '.' && ch != ',') {
                return false
            }
        }
        return hasDigit
    }

    /**
     * Returns the list of suffix suggestions for numbers:
     * Starting with "/-" and "kg", followed by relevant entries from the personal dictionary.
     */
    fun getSuffixes(context: Context?): List<String> {
        val result = LinkedHashSet<String>()
        // User requested "/-" and "kg" as the primary suggestions
        result.addAll(DEFAULT_SUFFIXES)

        if (context != null) {
            val userDictSuffixes = getUserDictionarySuffixes(context)
            result.addAll(userDictSuffixes)
        }

        return result.toList()
    }

    private fun getUserDictionarySuffixes(context: Context): List<String> {
        val now = System.currentTimeMillis()
        if (now - lastFetchTimestamp < CACHE_TTL_MS) {
            return cachedUserSuffixes
        }

        val list = mutableListOf<String>()
        var cursor: Cursor? = null
        try {
            val projection = arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT)
            cursor = context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                projection,
                null,
                null,
                "${UserDictionary.Words.FREQUENCY} DESC"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val wordIndex = cursor.getColumnIndex(UserDictionary.Words.WORD)
                val shortcutIndex = cursor.getColumnIndex(UserDictionary.Words.SHORTCUT)
                while (!cursor.isAfterLast) {
                    val word = if (wordIndex >= 0) cursor.getString(wordIndex) else null
                    val shortcut = if (shortcutIndex >= 0) cursor.getString(shortcutIndex) else null

                    if (!word.isNullOrBlank()) {
                        val trimmedWord = word.trim()
                        val isNumShortcut = shortcut != null && (
                            shortcut.equals("num", ignoreCase = true) ||
                            shortcut.equals("number", ignoreCase = true) ||
                            shortcut == "#" ||
                            shortcut.all { it.isDigit() }
                        )
                        val isUnitOrSymbol = KNOWN_UNITS.contains(trimmedWord.lowercase()) ||
                            trimmedWord.startsWith('/') ||
                            trimmedWord.startsWith('-') ||
                            (trimmedWord.length <= 4 && trimmedWord.all { it.isLetter() || !it.isWhitespace() })

                        if (isNumShortcut || isUnitOrSymbol) {
                            if (!list.contains(trimmedWord)) {
                                list.add(trimmedWord)
                            }
                        }
                    }
                    cursor.moveToNext()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query personal dictionary for number suffixes", e)
        } finally {
            try {
                cursor?.close()
            } catch (ignored: Exception) {}
        }

        cachedUserSuffixes = list
        lastFetchTimestamp = now
        return list
    }
}
