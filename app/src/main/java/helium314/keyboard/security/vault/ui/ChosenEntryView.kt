// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.security.VaultSessionManager
import helium314.keyboard.security.vault.crypto.VaultCryptoManager
import helium314.keyboard.security.vault.data.SecurityVaultDao
import helium314.keyboard.security.vault.data.VaultAttachmentEntity
import helium314.keyboard.security.vault.data.VaultEntryEntity
import helium314.keyboard.security.vault.totp.TotpGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("CustomViewStyleable")
class ChosenEntryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = R.attr.clipboardHistoryViewStyle
) : LinearLayout(context, attrs, defStyle) {

    private lateinit var btnBack: ImageButton
    private lateinit var btnLock: ImageButton
    private lateinit var txtTitle: TextView
    private lateinit var txtSubtitle: TextView

    private lateinit var btnActionNote: LinearLayout
    private lateinit var iconActionNote: ImageView
    private lateinit var btnActionUsername: LinearLayout
    private lateinit var iconActionUsername: ImageView
    private lateinit var btnActionPassword: LinearLayout
    private lateinit var iconActionPassword: ImageView
    private lateinit var btnActionTotp: LinearLayout
    private lateinit var totpCircle: TotpCircleProgressView
    private lateinit var btnActionAttachment: LinearLayout
    private lateinit var iconActionAttachment: ImageView

    private lateinit var btnUtilAbc: Button
    private lateinit var btnUtilSpace: Button
    private lateinit var btnUtilBackspace: ImageButton
    private lateinit var btnUtilEnter: ImageButton

    private var keyboardActionListener: KeyboardActionListener? = null
    private var currentEntry: VaultEntryEntity? = null
    private var entryAttachments: List<VaultAttachmentEntity> = emptyList()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var totpTickerJob: Job? = null

    companion object {
        private const val TAG = "ChosenEntryView"
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        btnBack = findViewById(R.id.btn_chosen_back)
        btnLock = findViewById(R.id.btn_chosen_lock)
        txtTitle = findViewById(R.id.txt_chosen_title)
        txtSubtitle = findViewById(R.id.txt_chosen_subtitle)

        btnActionNote = findViewById(R.id.btn_action_note)
        iconActionNote = findViewById(R.id.icon_action_note)
        btnActionUsername = findViewById(R.id.btn_action_username)
        iconActionUsername = findViewById(R.id.icon_action_username)
        btnActionPassword = findViewById(R.id.btn_action_password)
        iconActionPassword = findViewById(R.id.icon_action_password)
        btnActionTotp = findViewById(R.id.btn_action_totp)
        totpCircle = findViewById(R.id.action_totp_circle)
        btnActionAttachment = findViewById(R.id.btn_action_attachment)
        iconActionAttachment = findViewById(R.id.icon_action_attachment)

        btnUtilAbc = findViewById(R.id.btn_util_abc)
        btnUtilSpace = findViewById(R.id.btn_util_space)
        btnUtilBackspace = findViewById(R.id.btn_util_backspace)
        btnUtilEnter = findViewById(R.id.btn_util_enter)

        setupListeners()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues())
        // Compact height profile: ~140dp–150dp or 60% of keyboard height
        val fullHeight = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues())
        val compactHeight = (fullHeight * 0.65f).toInt().coerceAtLeast((140 * res.displayMetrics.density).toInt())
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(compactHeight, MeasureSpec.EXACTLY)
        super.onMeasure(widthSpec, heightSpec)
        setMeasuredDimension(width, compactHeight)
    }

    fun bindEntry(entry: VaultEntryEntity, listener: KeyboardActionListener) {
        this.currentEntry = entry
        this.keyboardActionListener = listener

        txtTitle.text = entry.title
        txtSubtitle.text = if (!entry.username.isNullOrBlank()) entry.username else "No username"

        val hasNotes = entry.notesEncrypted != null && entry.notesEncrypted.isNotEmpty()
        btnActionNote.alpha = if (hasNotes) 1f else 0.45f

        val hasTotp = entry.totpSecretEncrypted != null && entry.totpSecretEncrypted.isNotEmpty()
        btnActionTotp.alpha = if (hasTotp) 1f else 0.45f

        // Query attachments asynchronously
        scope.launch {
            val attachments = withContext(Dispatchers.IO) {
                SecurityVaultDao.getInstance(context).getAttachmentsForEntry(entry.entryUuid)
            }
            entryAttachments = attachments
            btnActionAttachment.alpha = if (attachments.isNotEmpty()) 1f else 0.45f
        }

        applyThemeColors()
        startTotpTicker()
        visibility = View.VISIBLE
    }

    fun stopChosenEntry() {
        totpTickerJob?.cancel()
        totpTickerJob = null
        currentEntry = null
        visibility = View.GONE
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            stopChosenEntry()
            KeyboardSwitcher.getInstance().setSecurityVaultExplorerKeyboard()
        }

        btnLock.setOnClickListener {
            VaultSessionManager.lockSecurity()
            stopChosenEntry()
            KeyboardSwitcher.getInstance().closeChosenEntry(true)
        }

        btnActionUsername.setOnClickListener {
            val entry = currentEntry ?: return@setOnClickListener
            if (!entry.username.isNullOrBlank()) {
                keyboardActionListener?.onTextInput(entry.username)
                LogCatcher.log('I', TAG, "Injected username into active field")
            } else {
                Toast.makeText(context, "Username is empty", Toast.LENGTH_SHORT).show()
            }
        }

        btnActionPassword.setOnClickListener {
            val entry = currentEntry ?: return@setOnClickListener
            val passChars = VaultCryptoManager.decryptToCharArray(entry.passwordEncrypted)
            if (passChars != null && passChars.isNotEmpty()) {
                keyboardActionListener?.onTextInput(String(passChars))
                VaultCryptoManager.zeroize(passChars)
                LogCatcher.log('I', TAG, "Injected password directly into field (buffer zeroized)")
            } else {
                Toast.makeText(context, "Password is empty", Toast.LENGTH_SHORT).show()
            }
        }

        btnActionTotp.setOnClickListener {
            val entry = currentEntry ?: return@setOnClickListener
            val totpSecret = VaultCryptoManager.decryptToString(entry.totpSecretEncrypted)
            if (totpSecret != null) {
                val config = TotpGenerator.parseConfig(totpSecret)
                val totpResult = if (config != null) TotpGenerator.generate(config) else null
                if (totpResult != null) {
                    keyboardActionListener?.onTextInput(totpResult.code)
                    LogCatcher.log('I', TAG, "Injected TOTP code into active field")
                }
            } else {
                Toast.makeText(context, "No TOTP secret configured", Toast.LENGTH_SHORT).show()
            }
        }

        btnActionNote.setOnClickListener {
            val entry = currentEntry ?: return@setOnClickListener
            val noteText = VaultCryptoManager.decryptToString(entry.notesEncrypted)
            if (!noteText.isNullOrBlank()) {
                showNoteDropUp(noteText)
            } else {
                Toast.makeText(context, "No notes attached to this entry", Toast.LENGTH_SHORT).show()
            }
        }

        btnActionAttachment.setOnClickListener {
            if (entryAttachments.isNotEmpty()) {
                showAttachmentDropUp(entryAttachments)
            } else {
                Toast.makeText(context, "No attachments on this entry", Toast.LENGTH_SHORT).show()
            }
        }

        // Utility Row
        btnUtilAbc.setOnClickListener {
            stopChosenEntry()
            KeyboardSwitcher.getInstance().closeChosenEntry(false)
        }

        btnUtilSpace.setOnClickListener {
            keyboardActionListener?.onCodeInput(Constants.CODE_SPACE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }

        btnUtilBackspace.setOnClickListener {
            keyboardActionListener?.onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }

        btnUtilEnter.setOnClickListener {
            keyboardActionListener?.onCodeInput(Constants.CODE_ENTER, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }
    }

    private fun startTotpTicker() {
        totpTickerJob?.cancel()
        val entry = currentEntry ?: return
        val totpSecret = VaultCryptoManager.decryptToString(entry.totpSecretEncrypted) ?: return

        totpTickerJob = scope.launch {
            val config = TotpGenerator.parseConfig(totpSecret) ?: return@launch
            while (isActive) {
                val res = TotpGenerator.generate(config)
                if (res != null) {
                    totpCircle.setProgress(res.progressFraction, res.remainingSeconds)
                }
                delay(500)
            }
        }
    }

    private fun showNoteDropUp(notes: String) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#333333"))
            val tv = TextView(context).apply {
                text = notes
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            addView(tv)
            val copyBtn = Button(context).apply {
                text = "Copy Note"
                setOnClickListener {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Vault Note", notes))
                    Toast.makeText(context, "Note copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            }
            addView(copyBtn)
        }
        val popupWindow = PopupWindow(
            container,
            (width * 0.85f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.showAtLocation(this, Gravity.CENTER, 0, 0)
    }

    private fun showAttachmentDropUp(attachments: List<VaultAttachmentEntity>) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#333333"))
            val title = TextView(context).apply {
                text = "Attached Files (${attachments.size})"
                setTextColor(Color.WHITE)
                textSize = 14f
                paint.isFakeBoldText = true
            }
            addView(title)
            for (att in attachments) {
                val item = TextView(context).apply {
                    text = "📎 ${att.filename} (${att.mimeType})"
                    setTextColor(Color.LTGRAY)
                    textSize = 12f
                    setPadding(8, 12, 8, 12)
                    setOnClickListener {
                        Toast.makeText(context, "Attachment: ${att.filename}", Toast.LENGTH_SHORT).show()
                    }
                }
                addView(item)
            }
        }
        val popupWindow = PopupWindow(container, (width * 0.85f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popupWindow.showAtLocation(this, Gravity.CENTER, 0, 0)
    }

    private fun applyThemeColors() {
        val colors = runCatching { Settings.getValues().mColors }.getOrNull()
        if (colors != null) {
            colors.setBackground(this, ColorType.MAIN_BACKGROUND)
            val textColor = colors.get(ColorType.KEY_TEXT)
            val hintColor = colors.get(ColorType.KEY_HINT_TEXT)
            val keyBg = colors.get(ColorType.KEY_BACKGROUND)

            txtTitle.setTextColor(textColor)
            txtSubtitle.setTextColor(hintColor)
            KeyboardTypeface.applyToTextView(txtTitle)
            KeyboardTypeface.applyToTextView(txtSubtitle)

            colors.setColor(btnBack, ColorType.KEY_ICON)
            colors.setColor(btnLock, ColorType.KEY_ICON)
            colors.setColor(iconActionNote, ColorType.KEY_ICON)
            colors.setColor(iconActionUsername, ColorType.KEY_ICON)
            colors.setColor(iconActionPassword, ColorType.KEY_ICON)
            colors.setColor(iconActionAttachment, ColorType.KEY_ICON)
            colors.setColor(btnUtilBackspace, ColorType.KEY_ICON)
            colors.setColor(btnUtilEnter, ColorType.KEY_ICON)

            btnUtilAbc.setTextColor(textColor)
            btnUtilSpace.setTextColor(textColor)
        } else {
            setBackgroundColor(Color.parseColor("#202124"))
            txtTitle.setTextColor(Color.WHITE)
            txtSubtitle.setTextColor(Color.LTGRAY)
            btnBack.setColorFilter(Color.WHITE)
            btnLock.setColorFilter(Color.WHITE)
        }
    }
}

