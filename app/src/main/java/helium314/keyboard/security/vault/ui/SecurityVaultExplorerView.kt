// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.security.VaultSessionManager
import helium314.keyboard.security.vault.data.SecurityVaultDao
import helium314.keyboard.security.vault.data.VaultEntryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("CustomViewStyleable")
class SecurityVaultExplorerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = R.attr.clipboardHistoryViewStyle
) : LinearLayout(context, attrs, defStyle), SecurityVaultDao.Listener {

    private lateinit var topBar: LinearLayout
    private lateinit var chipAll: TextView
    private lateinit var chipRecent: TextView
    private lateinit var chipFolders: TextView
    private lateinit var btnSortToggle: ImageButton
    private lateinit var timerContainer: LinearLayout
    private lateinit var txtSessionTimer: TextView
    private lateinit var btnLock: ImageButton
    private lateinit var btnClose: ImageButton

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: AccordionFolderAdapter

    private var keyboardActionListener: KeyboardActionListener? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var tickerJob: Job? = null
    private var basePaddingBottom = 0
    private var navBarBottomInset = 0

    companion object {
        private const val TAG = "SecurityVaultExplorerView"
    }

    init {
        orientation = VERTICAL
        fitsSystemWindows = true
        basePaddingBottom = paddingBottom
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        topBar = findViewById(R.id.vault_top_bar)
        chipAll = findViewById(R.id.chip_filter_all)
        chipRecent = findViewById(R.id.chip_filter_recent)
        chipFolders = findViewById(R.id.chip_filter_folders)
        btnSortToggle = findViewById(R.id.btn_sort_toggle)
        timerContainer = findViewById(R.id.vault_timer_container)
        txtSessionTimer = findViewById(R.id.txt_session_timer)
        btnLock = findViewById(R.id.btn_vault_lock)
        btnClose = findViewById(R.id.btn_vault_close)
        recyclerView = findViewById(R.id.recycler_vault_items)
        emptyText = findViewById(R.id.txt_vault_empty)

        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = AccordionFolderAdapter(context) { entry ->
            handleEntrySelected(entry)
        }
        recyclerView.adapter = adapter

        // Filter pills
        chipAll.setOnClickListener {
            adapter.setFilterMode(AccordionFolderAdapter.FilterMode.ALL)
            updateChipHighlight(chipAll)
        }
        chipRecent.setOnClickListener {
            adapter.setFilterMode(AccordionFolderAdapter.FilterMode.RECENT)
            updateChipHighlight(chipRecent)
        }
        chipFolders.setOnClickListener {
            adapter.setFilterMode(AccordionFolderAdapter.FilterMode.FOLDERS)
            updateChipHighlight(chipFolders)
        }

        btnSortToggle.setOnClickListener {
            val newSort = adapter.toggleSortMode()
            val sortIcon = if (newSort == AccordionFolderAdapter.SortMode.TIME) {
                R.drawable.ic_sort_chronologically
            } else {
                R.drawable.ic_sort_alphabetically
            }
            btnSortToggle.setImageResource(sortIcon)
            btnSortToggle.alpha = if (newSort == AccordionFolderAdapter.SortMode.TIME) 1f else 0.8f
        }
        updateChipHighlight(chipAll)

        // Zero-Trust Lock: Closing modal or pressing lock always immediately locks session
        btnClose.setOnClickListener {
            closeAndLock()
        }

        btnLock.setOnClickListener {
            closeAndLock()
        }

        SecurityVaultDao.getInstance(context).addListener(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SecurityVaultDao.getInstance(context).addListener(this)
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
            startCountdownTicker()
        }
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
            setPadding(paddingLeft, paddingTop, paddingRight, basePaddingBottom + effectiveInset)
            requestLayout()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopCountdownTicker()
        SecurityVaultDao.getInstance(context).removeListener(this)
    }

    private fun startCountdownTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val remainingMs = VaultSessionManager.getSecuritySessionRemainingMs()
                if (remainingMs <= 0L) {
                    txtSessionTimer.text = "00:00"
                    LogCatcher.log('I', TAG, "Security Vault session expired: auto-locking immediately")
                    closeAndLock()
                    break
                }
                val totalSeconds = (remainingMs + 999) / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                txtSessionTimer.text = String.format("%02d:%02d", minutes, seconds)
                delay(1000L)
            }
        }
    }

    private fun stopCountdownTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues()) + paddingTop + paddingBottom
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        super.onMeasure(widthSpec, heightSpec)
        setMeasuredDimension(width, height)
    }

    @JvmOverloads
    fun startExplorer(
        listener: KeyboardActionListener,
        editorInfo: EditorInfo? = null,
        keyVisualAttr: KeyVisualAttributes? = null
    ) {
        this.keyboardActionListener = listener
        applyThemeColors()
        updateChipHighlight(chipAll)
        loadData()
        startCountdownTicker()
        visibility = View.VISIBLE
    }

    fun stopExplorer() {
        stopCountdownTicker()
        visibility = View.GONE
    }

    fun closeWithoutLock() {
        // Enforce Interpretation 1: close the security gap on modal exit
        closeAndLock()
    }

    fun closeAndLock() {
        LogCatcher.log('I', TAG, "Locking Security Vault immediately on modal exit/expiry")
        stopCountdownTicker()
        VaultSessionManager.lockSecurity()
        stopExplorer()
        KeyboardSwitcher.getInstance().closeSecurityVaultExplorer(true)
    }

    private fun handleEntrySelected(entry: VaultEntryEntity) {
        LogCatcher.log('I', TAG, "Entry selected: ${entry.title.take(1)}***")
        stopExplorer()
        KeyboardSwitcher.getInstance().setChosenEntryKeyboard(entry)
    }

    private fun loadData() {
        scope.launch {
            val dao = SecurityVaultDao.getInstance(context)
            val groups = withContext(Dispatchers.IO) { dao.getAllGroups() }
            val entries = withContext(Dispatchers.IO) { dao.getAllEntries() }
            val recent = withContext(Dispatchers.IO) { dao.getRecentEntries(15) }

            adapter.setData(groups, entries, recent)
            val isEmpty = entries.isEmpty() && groups.isEmpty()
            emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
            recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    override fun onSecurityVaultDataChanged() {
        if (visibility == View.VISIBLE) {
            loadData()
        }
    }

    private fun updateChipHighlight(activeChip: TextView) {
        val colors = runCatching { Settings.getValues().mColors }.getOrNull()
        val textColor = colors?.get(ColorType.KEY_TEXT) ?: Color.WHITE
        val hintColor = colors?.get(ColorType.KEY_HINT_TEXT) ?: Color.LTGRAY
        val accentFill = colors?.get(ColorType.ACTION_KEY_BACKGROUND)
            ?: colors?.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
            ?: Color.parseColor("#3898EC")
        val strokeWidth = (1.2 * resources.displayMetrics.density).toInt().coerceAtLeast(1)

        listOf(chipAll, chipRecent, chipFolders).forEach { chip ->
            if (chip == activeChip) {
                chip.setBackgroundResource(R.drawable.bg_vault_chip_active)
                chip.background?.mutate()?.setTint(accentFill)
                chip.setTextColor(Color.WHITE)
                chip.paint.isFakeBoldText = true
            } else {
                chip.setBackgroundResource(R.drawable.bg_vault_chip_inactive)
                val bg = chip.background?.mutate()
                if (bg is GradientDrawable) {
                    bg.setStroke(strokeWidth, hintColor)
                }
                chip.setTextColor(hintColor)
                chip.paint.isFakeBoldText = false
            }
        }
    }

    private fun applyThemeColors() {
        val colors = runCatching { Settings.getValues().mColors }.getOrNull()
        val strokeWidth = (1.2 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        if (colors != null) {
            colors.setBackground(this, ColorType.MAIN_BACKGROUND)
            colors.setBackground(topBar, ColorType.STRIP_BACKGROUND)
            colors.setColor(btnLock, ColorType.KEY_ICON)
            colors.setColor(btnClose, ColorType.KEY_ICON)
            colors.setColor(btnSortToggle, ColorType.KEY_ICON)

            val hintColor = colors.get(ColorType.KEY_HINT_TEXT)
            val circleBg = btnSortToggle.background?.mutate()
            if (circleBg is GradientDrawable) {
                circleBg.setStroke(strokeWidth, hintColor)
            }

            val textColor = colors.get(ColorType.KEY_TEXT)
            emptyText.setTextColor(textColor)
            KeyboardTypeface.applyToTextView(emptyText)
            KeyboardTypeface.applyToTextView(chipAll)
            KeyboardTypeface.applyToTextView(chipRecent)
            KeyboardTypeface.applyToTextView(chipFolders)
        } else {
            setBackgroundColor(Color.parseColor("#202124"))
            topBar.setBackgroundColor(Color.parseColor("#303134"))
            btnLock.setColorFilter(Color.WHITE)
            btnClose.setColorFilter(Color.WHITE)
            btnSortToggle.setColorFilter(Color.WHITE)
            val circleBg = btnSortToggle.background?.mutate()
            if (circleBg is GradientDrawable) {
                circleBg.setStroke(strokeWidth, Color.parseColor("#55888888"))
            }
            emptyText.setTextColor(Color.WHITE)
        }
    }
}
