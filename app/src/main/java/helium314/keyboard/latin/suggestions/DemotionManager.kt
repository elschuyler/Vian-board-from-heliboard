package helium314.keyboard.latin.suggestions

import android.content.Context
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.Collections
import java.util.HashSet

object DemotionManager {
    private const val TAG = "DemotionManager"
    private const val FILE_NAME = "demoted_words.txt"
    private val demotedWords: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (file.exists()) {
                    file.readLines().forEach { line ->
                        val trimmed = line.trim().lowercase()
                        if (trimmed.isNotEmpty()) {
                            demotedWords.add(trimmed)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading demoted words", e)
            } finally {
                initialized = true
            }
        }
    }

    fun isDemoted(word: String?): Boolean {
        if (word.isNullOrEmpty()) return false
        return demotedWords.contains(word.lowercase())
    }

    fun hasDemotions(): Boolean = demotedWords.isNotEmpty()

    fun demote(context: Context, word: String) {
        val lower = word.lowercase()
        demotedWords.add(lower)
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) {
                file.createNewFile()
            }
            file.appendText("$lower\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting demoted word: $word", e)
        }
    }

    fun clear(context: Context) {
        demotedWords.clear()
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing demoted words", e)
        }
    }
}
