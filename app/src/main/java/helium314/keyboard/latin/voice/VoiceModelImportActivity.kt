// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.LogCatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceModelImportActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            LogCatcher.i("VoiceModelImportActivity", "Document picker cancelled by user")
            val callback = VoiceModelImportBridge.onImportResult
            VoiceModelImportBridge.onImportResult = null
            callback?.invoke(false, null)
            finish()
            overridePendingTransition(0, 0)
            return@registerForActivityResult
        }

        LogCatcher.i("VoiceModelImportActivity", "Selected file URI: $uri")
        scope.launch(Dispatchers.IO) {
            val result = VoiceModelManager.importModel(applicationContext, uri)
            withContext(Dispatchers.Main) {
                val callback = VoiceModelImportBridge.onImportResult
                VoiceModelImportBridge.onImportResult = null
                result.onSuccess { info ->
                    LogCatcher.i("VoiceModelImportActivity", "Whisper model successfully imported: ${info.fileName} (${info.formattedSize})")
                    Toast.makeText(
                        applicationContext,
                        applicationContext.getString(R.string.voice_input_model_imported, info.fileName, info.formattedSize),
                        Toast.LENGTH_LONG
                    ).show()
                    callback?.invoke(true, info)
                }.onFailure { err ->
                    LogCatcher.w("VoiceModelImportActivity", "Failed to import Whisper model: ${err.message}")
                    Toast.makeText(
                        applicationContext,
                        applicationContext.getString(R.string.voice_input_import_error, err.message ?: "Unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                    callback?.invoke(false, null)
                }
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        try {
            openDocumentLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            LogCatcher.e("VoiceModelImportActivity", "Failed to launch document picker", e)
            val callback = VoiceModelImportBridge.onImportResult
            VoiceModelImportBridge.onImportResult = null
            callback?.invoke(false, null)
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
