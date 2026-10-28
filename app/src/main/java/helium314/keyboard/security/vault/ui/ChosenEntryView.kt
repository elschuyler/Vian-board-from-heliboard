// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
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
    private lateinit var txtChosenTimer: TextView

    private lateinit var btnActionNote: LinearLayout
    private lateinit var iconActionNote: ImageView
    private lateinit var btnActionAttachment: LinearLayout
    private lateinit var iconActionAttachment: ImageView
    private lateinit var btnActionPassword: LinearLayout
    private lateinit var iconActionPassword: ImageView
    private lateinit var btnActionUsername: LinearLayout
    private lateinit var iconActionUsername: ImageView
    private lateinit var btnActionTotp: LinearLayout
    private lateinit var totpCircle: TotpCircleProgressView

    private var bottomRowKeyboard: MainKeyboardView? = null
    private var keyBackgroundId: Int = 0

    private var keyboardActionListener: KeyboardActionListener? = null
    private var currentEntry: VaultEntryEntity? = null
    private var entryAttachments: List<VaultAttachmentEntity> = emptyList()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var totpTickerJob: Job? = null
    private var sessionTickerJob: Job? = null
    private var activePopupWindow: PopupWindow? = null
    private var navBarBottomInset: Int = 0

    companion object {
        private const val TAG = "ChosenEntryView"
    }

    init {
        orientation = VERTICAL
        fitsSystemWindows = false
        setPadding(0, 0, 0, 0)
        val keyboardViewAttr = context.obtainStyledAttributes(attrs, R.styleable.KeyboardView, defStyle, R.style.KeyboardView)
        keyBackgroundId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val navBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        applyBottomInset(navBottom)
        return super.onApplyWindowInsets(insets)
    }

    private fun applyBottomInset(bottomInset: Int) {
        val effectiveInset = if (Settings.getValues().mIsFloatingKeyboard) 0 else bottomInset
        if (navBarBottomInset != effectiveInset) {
            navBarBottomInset = effectiveInset
            setPadding(paddingLeft, paddingTop, paddingRight, effectiveInset)
            requestLayout()
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        btnBack = findViewById(R.id.btn_chosen_back)
        btnLock = findViewById(R.id.btn_chosen_lock)
        txtTitle = findViewById(R.id.txt_chosen_title)
        txtSubtitle = findViewById(R.id.txt_chosen_subtitle)
        txtChosenTimer = findViewById(R.id.txt_chosen_timer)

        btnActionNote = findViewById(R.id.btn_action_note)
        iconActionNote = findViewById(R.id.icon_action_note)
        btnActionAttachment = findViewById(R.id.btn_action_attachment)
        iconActionAttachment = findViewById(R.id.icon_action_attachment)
        btnActionPassword = findViewById(R.id.btn_action_password)
        iconActionPassword = findViewById(R.id.icon_action_password)
        btnActionUsername = findViewById(R.id.btn_action_username)
        iconActionUsername = findViewById(R.id.icon_action_username)
        btnActionTotp = findViewById(R.id.btn_action_totp)
        totpCircle = findViewById(R.id.action_totp_circle)

        bottomRowKeyboard = findViewById(R.id.bottom_row_keyboard)

        setupListeners()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues())
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)

        // Measure top bar
        val topRow = findViewById<View>(R.id.chosen_top_row)
        topRow?.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val topHeight = topRow?.measuredHeight ?: (38 * res.displayMetrics.density).toInt()

        // Measure action row
        val actionRow = findViewById<View>(R.id.chosen_action_row)
        actionRow?.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val actionHeight = actionRow?.measuredHeight ?: (44 * res.displayMetrics.density).toInt()

        // Measure bottom row keyboard
        val kbView = bottomRowKeyboard
        kbView?.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val kbHeight = kbView?.measuredHeight ?: (50 * res.displayMetrics.density).toInt()

        // Total exact height: zero vertical gap between action row and bottom row
        val totalHeight = topHeight + actionHeight + kbHeight + paddingTop + paddingBottom
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY)
        super.onMeasure(widthSpec, exactHeightSpec)
        setMeasuredDimension(width, totalHeight)
    }

    @JvmOverloads
    fun bindEntry(
        entry: VaultEntryEntity,
        listener: KeyboardActionListener,
        editorInfo: EditorInfo? = null,
        keyVisualAttr: KeyVisualAttributes? = null
    ) {
        this.currentEntry = entry
        this.keyboardActionListener = listener
        setupBottomRowKeyboard(editorInfo, listener)

        txtTitle.text = entry.title
        txtSubtitle.text = if (!entry.username.isNullOrBlank()) entry.username else "No username"

        val hasNotes = entry.notesEncrypted != null && entry.notesEncrypted.isNotEmpty()
        btnActionNote.alpha = if (hasNotes) 1f else 0.45f

        val hasTotp = entry.totpSecretEncrypted != null && entry.totpSecretEncrypted.isNotEmpty()
        btnActionTotp.alpha = if (hasTotp) 1f else 0.45f
        if (hasTotp) {
            startTotpTicker()
        }

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
        startSessionTicker()
        visibility = View.VISIBLE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            val windowMetrics = wm?.currentWindowMetrics
            val windowInsets = windowMetrics?.windowInsets
            if (windowInsets != null) {
                val insets = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
                )
                applyBottomInset(insets.bottom)
            }
        }
        if (visibility == View.VISIBLE) {
            startSessionTicker()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        activePopupWindow?.dismiss()
        activePopupWindow = null
        stopSessionTicker()
        totpTickerJob?.cancel()
        totpTickerJob = null
    }

    private fun startSessionTicker() {
        sessionTickerJob?.cancel()
        sessionTickerJob = scope.launch {
            while (isActive) {
                val remainingMs = VaultSessionManager.getSecuritySessionRemainingMs()
                if (remainingMs <= 0L) {
                    txtChosenTimer.text = "00:00"
                    LogCatcher.log('I', TAG, "Security Vault session expired in ChosenEntryView: auto-locking immediately")
                    activePopupWindow?.dismiss()
                    activePopupWindow = null
                    VaultSessionManager.lockSecurity()
                    stopChosenEntry()
                    KeyboardSwitcher.getInstance().closeChosenEntry(true)
                    break
                }
                val totalSeconds = (remainingMs + 999) / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                txtChosenTimer.text = String.format("%02d:%02d", minutes, seconds)
                delay(1000L)
            }
        }
    }

    private fun stopSessionTicker() {
        sessionTickerJob?.cancel()
        sessionTickerJob = null
    }

    fun stopChosenEntry() {
        activePopupWindow?.dismiss()
        activePopupWindow = null
        stopSessionTicker()
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
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo?, listener: KeyboardActionListener) {
        val keyboardView = bottomRowKeyboard ?: return
        val wrappedListener = object : KeyboardActionListener by listener {
            override fun onCodeInput(code: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
                if (code == KeyCode.ALPHA) {
                    stopChosenEntry()
                    KeyboardSwitcher.getInstance().closeChosenEntry(false)
                    return
                }
                listener.onCodeInput(code, x, y, isKeyRepeat)
            }
        }
        keyboardView.setKeyboardActionListener(wrappedListener)
        PointerTracker.switchTo(keyboardView)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
        val keyboard = kls.getKeyboard(KeyboardElement.CLIPBOARD_BOTTOM_ROW)
        keyboardView.setKeyboard(keyboard)
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
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#28292C"))
                cornerRadius = 14 * density
                setStroke((1 * density).toInt(), Color.parseColor("#44FFFFFF"))
            }
            background = bg
        }

        var popupWindow: PopupWindow? = null

        // Header with title and close [X]
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val title = TextView(context).apply {
                text = "Notes"
                setTextColor(Color.WHITE)
                textSize = 15f
                paint.isFakeBoldText = true
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(title)
            val closeBtn = TextView(context).apply {
                text = "✕"
                setTextColor(Color.LTGRAY)
                textSize = 16f
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                setOnClickListener { popupWindow?.dismiss() }
            }
            addView(closeBtn)
        }
        container.addView(header)

        // Scrollable note body
        val scrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
            val tv = TextView(context).apply {
                text = notes
                setTextColor(Color.parseColor("#E0E0E0"))
                textSize = 13f
                setTextIsSelectable(true)
            }
            addView(tv)
        }
        container.addView(scrollView)

        // Actions row: Insert Note and Copy Note
        val actionRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END

            val insertBtn = Button(context).apply {
                text = "Insert Note"
                textSize = 12f
                setTextColor(Color.WHITE)
                val btnBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#3898EC"))
                    cornerRadius = 8 * density
                }
                background = btnBg
                setOnClickListener {
                    keyboardActionListener?.onTextInput(notes)
                    Toast.makeText(context, "Note inserted", Toast.LENGTH_SHORT).show()
                    popupWindow?.dismiss()
                }
            }
            addView(insertBtn)

            val spacer = View(context).apply {
                layoutParams = LayoutParams((8 * density).toInt(), 1)
            }
            addView(spacer)

            val copyBtn = Button(context).apply {
                text = "Copy Note"
                textSize = 12f
                setTextColor(Color.WHITE)
                val btnBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#444444"))
                    cornerRadius = 8 * density
                }
                background = btnBg
                setOnClickListener {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Vault Note", notes))
                    Toast.makeText(context, "Note copied to clipboard", Toast.LENGTH_SHORT).show()
                    popupWindow?.dismiss()
                }
            }
            addView(copyBtn)
        }
        container.addView(actionRow)

        val popupWidth = (ResourceUtils.getKeyboardWidth(context, Settings.getValues()) * 0.9f).toInt()
        activePopupWindow?.dismiss()
        popupWindow = PopupWindow(
            container,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 16 * density
            setOnDismissListener {
                if (activePopupWindow === this) activePopupWindow = null
            }
        }
        activePopupWindow = popupWindow
        popupWindow.showAtLocation(this, Gravity.CENTER, 0, 0)
    }

    private fun showAttachmentDropUp(attachments: List<VaultAttachmentEntity>) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#28292C"))
                cornerRadius = 14 * density
                setStroke((1 * density).toInt(), Color.parseColor("#44FFFFFF"))
            }
            background = bg
        }

        var popupWindow: PopupWindow? = null

        // Header with title and close [X]
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val title = TextView(context).apply {
                text = "Attached Files (${attachments.size})"
                setTextColor(Color.WHITE)
                textSize = 15f
                paint.isFakeBoldText = true
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(title)
            val closeBtn = TextView(context).apply {
                text = "✕"
                setTextColor(Color.LTGRAY)
                textSize = 16f
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                setOnClickListener { popupWindow?.dismiss() }
            }
            addView(closeBtn)
        }
        container.addView(header)

        // List of attachments
        val scrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * density).toInt()
            }
            val listLayout = LinearLayout(context).apply {
                orientation = VERTICAL
                for (att in attachments) {
                    val item = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val itemBg = GradientDrawable().apply {
                            setColor(Color.parseColor("#38393D"))
                            cornerRadius = 8 * density
                        }
                        background = itemBg
                        setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = (6 * density).toInt()
                        }
                        isClickable = true
                        isFocusable = true

                        val iconView = TextView(context).apply {
                            text = "📎"
                            textSize = 14f
                        }
                        addView(iconView)

                        val infoCol = LinearLayout(context).apply {
                            orientation = VERTICAL
                            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                                marginStart = (8 * density).toInt()
                            }
                            val fname = TextView(context).apply {
                                text = att.filename
                                setTextColor(Color.WHITE)
                                textSize = 13f
                                paint.isFakeBoldText = true
                                maxLines = 1
                            }
                            addView(fname)
                            val fmeta = TextView(context).apply {
                                val sizeKb = ((att.dataBlobEncrypted?.size ?: 0) / 1024).coerceAtLeast(1)
                                text = "${att.mimeType} • ${sizeKb} KB"
                                setTextColor(Color.parseColor("#AAAAAA"))
                                textSize = 11f
                                maxLines = 1
                            }
                            addView(fmeta)
                        }
                        addView(infoCol)

                        setOnClickListener {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Attachment Filename", att.filename))
                            Toast.makeText(context, "Copied filename: ${att.filename}", Toast.LENGTH_SHORT).show()
                            popupWindow?.dismiss()
                        }
                    }
                    addView(item)
                }
            }
            addView(listLayout)
        }
        container.addView(scrollView)

        val popupWidth = (ResourceUtils.getKeyboardWidth(context, Settings.getValues()) * 0.9f).toInt()
        activePopupWindow?.dismiss()
        popupWindow = PopupWindow(
            container,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 16 * density
            setOnDismissListener {
                if (activePopupWindow === this) activePopupWindow = null
            }
        }
        activePopupWindow = popupWindow
        popupWindow.showAtLocation(this, Gravity.CENTER, 0, 0)
    }

    private fun applyThemeColors() {
        val colors = runCatching { Settings.getValues().mColors }.getOrNull()
        if (colors != null) {
            colors.setBackground(this, ColorType.MAIN_BACKGROUND)
            val textColor = colors.get(ColorType.KEY_TEXT)
            val hintColor = colors.get(ColorType.KEY_HINT_TEXT)

            txtTitle.setTextColor(textColor)
            txtSubtitle.setTextColor(hintColor)
            KeyboardTypeface.applyToTextView(txtTitle)
            KeyboardTypeface.applyToTextView(txtSubtitle)

            colors.setColor(btnBack, ColorType.KEY_ICON)
            colors.setColor(btnLock, ColorType.KEY_ICON)
            colors.setColor(iconActionNote, ColorType.KEY_ICON)
            colors.setColor(iconActionAttachment, ColorType.KEY_ICON)
            colors.setColor(iconActionPassword, ColorType.KEY_ICON)
            colors.setColor(iconActionUsername, ColorType.KEY_ICON)
            totpCircle.setColors(colors.get(ColorType.KEY_ICON), Color.parseColor("#44888888"), textColor)

            // Apply keyBackground and colors to all 5 mid-level action buttons so they have the exact button shape of the main keyboard keys
            listOf(btnActionNote, btnActionAttachment, btnActionPassword, btnActionUsername, btnActionTotp).forEach { btn ->
                if (keyBackgroundId != 0) {
                    btn.setBackgroundResource(keyBackgroundId)
                }
                colors.setBackground(btn, ColorType.KEY_BACKGROUND)
            }

            findViewById<TextView>(R.id.label_action_note)?.let {
                it.setTextColor(textColor)
                KeyboardTypeface.applyToTextView(it)
            }
            findViewById<TextView>(R.id.label_action_attachment)?.let {
                it.setTextColor(textColor)
                KeyboardTypeface.applyToTextView(it)
            }
            findViewById<TextView>(R.id.label_action_password)?.let {
                it.setTextColor(textColor)
                KeyboardTypeface.applyToTextView(it)
            }
            findViewById<TextView>(R.id.label_action_username)?.let {
                it.setTextColor(textColor)
                KeyboardTypeface.applyToTextView(it)
            }
            findViewById<TextView>(R.id.label_action_totp)?.let {
                it.setTextColor(textColor)
                KeyboardTypeface.applyToTextView(it)
            }
        } else {
            setBackgroundColor(Color.parseColor("#202124"))
            txtTitle.setTextColor(Color.WHITE)
            txtSubtitle.setTextColor(Color.LTGRAY)
            btnBack.setColorFilter(Color.WHITE)
            btnLock.setColorFilter(Color.WHITE)
        }
    }
}
