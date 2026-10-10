// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.Intent
import helium314.keyboard.latin.utils.LogCatcher

object VoiceModelImportBridge {
    private const val TAG = "VoiceModelImportBridge"
    var onImportResult: ((Boolean, VoiceModelInfo?) -> Unit)? = null

    fun launchImport(context: Context, callback: (Boolean, VoiceModelInfo?) -> Unit) {
        LogCatcher.i(TAG, "Requesting Whisper model import file picker")
        onImportResult = callback
        val intent = Intent(context, VoiceModelImportActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        context.startActivity(intent)
    }
}
