// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import android.content.Context
import android.graphics.Paint
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

object SupportedEmojis {
    @Volatile
    private var loaded = false
    @Volatile
    private var unsupportedEmojis: Set<String> = emptySet()

    fun ensureLoaded(context: Context) {
        if (!loaded) {
            load(context.applicationContext ?: context)
        }
    }

    @Synchronized
    fun load(context: Context) {
        determineMaxSdk(context)
        val maxSdk = context.prefs().getInt(Settings.PREF_EMOJI_MAX_SDK, 0)
        val nextUnsupported = hashSetOf<String>()
        context.assets.open("emoji/minApi.txt").reader().use { reader ->
            reader.readLines().forEach {
                val s = it.split(" ")
                val minApi = s.first().toInt()
                if (minApi > maxSdk)
                    nextUnsupported.addAll(s.drop(1))
            }
        }
        unsupportedEmojis = nextUnsupported
        loaded = true
    }

    private fun determineMaxSdk(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (context.prefs().contains(Settings.PREF_EMOJI_MAX_SDK)) return
        val paint = Paint()
        (KeyboardTypeface.emojiTypeface() ?: KeyboardTypeface.customTypeface())
            ?.let { paint.setTypeface(it) }
        val maxApi = context.assets.open("emoji/minApi.txt").reader().use { reader ->
            reader.readLines().maxOf {
                val s = it.split(" ")
                val supported = paint.hasGlyph(s[1])
                if (supported) s.first().toInt() else 0
            }
        }
        val newMax = maxApi.coerceAtLeast(Build.VERSION.SDK_INT)
        context.prefs().edit { putInt(Settings.PREF_EMOJI_MAX_SDK, newMax) }
    }

    fun isUnsupported(emoji: String) = emoji in unsupportedEmojis
}
