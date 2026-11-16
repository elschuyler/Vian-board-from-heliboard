// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.LogCatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceModelImportActivity : ComponentActivity() {

    companion object {
        private const val KEY_PICKER_LAUNCHED = "picker_launched"
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var pickerLaunched = false

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            LogCatcher.i("VoiceModelImportActivity", "Document picker cancelled by user")
            val callback = VoiceModelImportBridge.onImportResult
            VoiceModelImportBridge.onImportResult = null
            KeyboardSwitcher.getInstance().setPendingVoiceLaunch(false)
            finish()
            overridePendingTransition(0, 0)
            callback?.invoke(false, null)
            return@registerForActivityResult
        }

        LogCatcher.i("VoiceModelImportActivity", "Selected file URI: $uri")
        scope.launch(Dispatchers.IO) {
            val result = VoiceModelManager.importModel(applicationContext, uri)
            withContext(Dispatchers.Main) {
                val callback = VoiceModelImportBridge.onImportResult
                VoiceModelImportBridge.onImportResult = null
                val switcher = KeyboardSwitcher.getInstance()
                result.onSuccess { info ->
                    LogCatcher.i("VoiceModelImportActivity", "Whisper model successfully imported: ${info.fileName} (${info.formattedSize})")
                    Toast.makeText(
                        applicationContext,
                        applicationContext.getString(R.string.voice_input_model_imported, info.fileName, info.formattedSize),
                        Toast.LENGTH_LONG
                    ).show()
                    switcher.setPendingVoiceLaunch(true)
                    finish()
                    overridePendingTransition(0, 0)
                    Handler(Looper.getMainLooper()).postDelayed({
                        callback?.invoke(true, info)
                        if (!switcher.isShowingVoiceInput()) {
                            switcher.setPendingVoiceLaunch(false)
                            switcher.setVoiceInputKeyboard()
                        }
                    }, 350L)
                }.onFailure { err ->
                    LogCatcher.w("VoiceModelImportActivity", "Failed to import Whisper model: ${err.message}")
                    Toast.makeText(
                        applicationContext,
                        applicationContext.getString(R.string.voice_input_import_error, err.message ?: "Unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                    switcher.setPendingVoiceLaunch(false)
                    finish()
                    overridePendingTransition(0, 0)
                    callback?.invoke(false, null)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        pickerLaunched = savedInstanceState?.getBoolean(KEY_PICKER_LAUNCHED, false) ?: false
        if (!pickerLaunched) {
            pickerLaunched = true
            try {
                openDocumentLauncher.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                LogCatcher.e("VoiceModelImportActivity", "Failed to launch document picker", e)
                val callback = VoiceModelImportBridge.onImportResult
                VoiceModelImportBridge.onImportResult = null
                KeyboardSwitcher.getInstance().setPendingVoiceLaunch(false)
                finish()
                overridePendingTransition(0, 0)
                callback?.invoke(false, null)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PICKER_LAUNCHED, pickerLaunched)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
