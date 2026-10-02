// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.context

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.security.VaultSessionManager
import helium314.keyboard.security.vault.crypto.VaultCryptoManager
import helium314.keyboard.security.vault.data.SecurityVaultDao
import helium314.keyboard.security.vault.data.VaultEntryEntity

/**
 * SecurityVaultContextSniffer detects active application packages and input field hints
 * on onStartInputView, matching them against stored vault credentials to provide instant,
 * low-friction auto-fill pills directly on the SuggestionStripView.
 */
object SecurityVaultContextSniffer {
    private const val TAG = "VaultContextSniffer"

    /**
     * Evaluates active EditorInfo package name and hints against the Security Vault database.
     */
    fun findMatchingEntries(context: Context, editorInfo: EditorInfo?): List<VaultEntryEntity> {
        if (editorInfo == null) return emptyList()
        val pkg = editorInfo.packageName ?: return emptyList()
        if (!VaultSessionManager.isPatternSet(context)) return emptyList()
        if (!context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
                .getBoolean("pref_vault_context_suggestions", true)) {
            return emptyList()
        }

        val results = mutableListOf<VaultEntryEntity>()
        val dao = SecurityVaultDao.getInstance(context)

        // 1. Exact or partial package match
        val pkgMatches = dao.findByPackageOrUrl(pkg)
        results.addAll(pkgMatches)

        // 2. Extract clean app keyword from package name (e.g., com.github.android -> github)
        val keyword = extractKeywordFromPackage(pkg)
        if (keyword.length >= 3) {
            val keywordMatches = dao.findByPackageOrUrl(keyword)
            for (m in keywordMatches) {
                if (results.none { it.entryUuid == m.entryUuid }) {
                    results.add(m)
                }
            }
        }

        // 3. Match hint text if provided (e.g., web domains in browser address bars or fields)
        val hint = editorInfo.hintText?.toString()?.trim()
        if (!hint.isNullOrBlank() && hint.length >= 4) {
            val hintMatches = dao.findByPackageOrUrl(hint)
            for (m in hintMatches) {
                if (results.none { it.entryUuid == m.entryUuid }) {
                    results.add(m)
                }
            }
        }

        if (results.isNotEmpty()) {
            LogCatcher.log('I', TAG, "Found ${results.size} context match(es) for pkg=$pkg")
        }
        return results
    }

    private fun extractKeywordFromPackage(pkg: String): String {
        val parts = pkg.split('.').filter { part ->
            part.lowercase() !in setOf("com", "org", "net", "io", "app", "android", "mobile", "client", "release", "beta")
        }
        return parts.firstOrNull { it.length >= 3 } ?: ""
    }

    /**
     * Constructs the responsive context pill view for SuggestionStripView.
     */
    @SuppressLint("SetTextI18n")
    fun createSuggestionPillView(
        latinIME: LatinIME,
        editorInfo: EditorInfo?,
        matches: List<VaultEntryEntity>,
        onDismiss: () -> Unit
    ): View {
        val context = latinIME
        val colors = runCatching { Settings.getValues().mColors }.getOrNull()
        val keyTextCol = colors?.get(ColorType.KEY_TEXT) ?: Color.WHITE
        val keyIconCol = colors?.get(ColorType.KEY_ICON) ?: Color.WHITE
        val stripBgCol = colors?.get(ColorType.STRIP_BACKGROUND) ?: Color.parseColor("#202124")
        val chipBgCol = colors?.get(ColorType.KEY_BACKGROUND) ?: Color.parseColor("#303134")

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(8.dpToPx(resources), 2.dpToPx(resources), 8.dpToPx(resources), 2.dpToPx(resources))
        }

        if (matches.size == 1) {
            val entry = matches[0]
            val pill = buildSinglePillView(context, entry, chipBgCol, keyTextCol, keyIconCol) {
                handleCredentialCommit(latinIME, editorInfo, entry)
            }
            root.addView(pill)
        } else {
            // Multiple accounts: show expandable dropdown pill
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val scrollContainer = HorizontalScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                isHorizontalScrollBarEnabled = false
                visibility = View.GONE
            }

            val accountsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            scrollContainer.addView(accountsRow)

            val summaryPill = buildPillLayout(context, chipBgCol).apply {
                val icon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_vpn_key)
                    setColorFilter(keyIconCol)
                    layoutParams = LinearLayout.LayoutParams(16.dpToPx(resources), 16.dpToPx(resources)).apply {
                        marginEnd = 6.dpToPx(resources)
                    }
                }
                val label = TextView(context).apply {
                    text = "${matches.size} Accounts ▼"
                    setTextColor(keyTextCol)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    KeyboardTypeface.applyToTextView(this)
                }
                addView(icon)
                addView(label)

                setOnClickListener {
                    visibility = View.GONE
                    scrollContainer.visibility = View.VISIBLE
                }
            }
            container.addView(summaryPill)

            for (entry in matches) {
                val accountPill = buildSinglePillView(context, entry, chipBgCol, keyTextCol, keyIconCol) {
                    handleCredentialCommit(latinIME, editorInfo, entry)
                }.apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 6.dpToPx(resources)
                }
                accountsRow.addView(accountPill)
            }
            container.addView(scrollContainer)
            root.addView(container)
        }

        // Close button to dismiss suggestions
        val closeBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_close_rounded)
            setColorFilter(keyIconCol)
            alpha = 0.7f
            val size = 28.dpToPx(resources)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = 8.dpToPx(resources)
            }
            setPadding(4.dpToPx(resources), 4.dpToPx(resources), 4.dpToPx(resources), 4.dpToPx(resources))
            setOnClickListener {
                onDismiss()
            }
        }
        root.addView(closeBtn)

        return root
    }

    private fun buildPillLayout(context: Context, bgColor: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * context.resources.displayMetrics.density
                setColor(bgColor)
            }
            val padH = 10.dpToPx(context.resources)
            val padV = 5.dpToPx(context.resources)
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true
        }
    }

    private fun buildSinglePillView(
        context: Context,
        entry: VaultEntryEntity,
        bgColor: Int,
        textCol: Int,
        iconCol: Int,
        onClick: () -> Unit
    ): LinearLayout {
        return buildPillLayout(context, bgColor).apply {
            val keyIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_vpn_key)
                setColorFilter(iconCol)
                layoutParams = LinearLayout.LayoutParams(16.dpToPx(resources), 16.dpToPx(resources)).apply {
                    marginEnd = 6.dpToPx(resources)
                }
            }
            val text = TextView(context).apply {
                val label = (entry.username ?: "").ifBlank { entry.title }
                this.text = label
                setTextColor(textCol)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                KeyboardTypeface.applyToTextView(this)
            }
            addView(keyIcon)
            addView(text)
            setOnClickListener {
                onClick()
            }
        }
    }

    private fun handleCredentialCommit(
        latinIME: LatinIME,
        editorInfo: EditorInfo?,
        entry: VaultEntryEntity
    ) {
        val switcher = KeyboardSwitcher.getInstance()
        if (VaultSessionManager.isSecuritySessionValid()) {
            executeDirectInjection(latinIME, editorInfo, entry)
        } else {
            // Trigger Stealth Pattern Unlock disguised over QWERTY keyboard
            switcher.showStealthPatternUnlock(
                { executeDirectInjection(latinIME, editorInfo, entry) },
                { LogCatcher.log('I', TAG, "Stealth unlock challenge dismissed by user") }
            )
        }
    }

    private fun executeDirectInjection(
        latinIME: LatinIME,
        editorInfo: EditorInfo?,
        entry: VaultEntryEntity
    ) {
        val inputType = editorInfo?.inputType ?: 0
        if (InputTypeUtils.isPasswordInputType(inputType)) {
            // Inject password into password field
            val passwordBlob = entry.passwordEncrypted
            if (passwordBlob != null) {
                val chars = VaultCryptoManager.decryptToCharArray(passwordBlob)
                if (chars != null) {
                    latinIME.onTextInput(String(chars))
                    VaultCryptoManager.zeroize(chars)
                    LogCatcher.log('I', TAG, "Directly injected decrypted password via stealth pill")
                }
            }
        } else {
            // Inject username into standard/email/username field
            if (!entry.username.isNullOrBlank()) {
                latinIME.onTextInput(entry.username)
                LogCatcher.log('I', TAG, "Directly injected username via stealth pill")
            } else {
                latinIME.onTextInput(entry.title)
            }
        }

        // Subtly trigger haptic feedback
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                latinIME.window?.window?.decorView?.performHapticFeedback(
                    HapticFeedbackConstants.CLOCK_TICK,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            } else {
                latinIME.window?.window?.decorView?.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            }
        } catch (_: Throwable) {}
    }
}
