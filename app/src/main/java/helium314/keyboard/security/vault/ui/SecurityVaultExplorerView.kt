// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.LogCatcher
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.security.VaultSessionManager
import helium314.keyboard.security.vault.data.SecurityVaultDao
import helium314.keyboard.security.vault.data.VaultEntryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private lateinit var btnLock: ImageButton
    private lateinit var btnClose: ImageButton

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: AccordionFolderAdapter

    private var keyboardActionListener: KeyboardActionListener? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val TAG = "SecurityVaultExplorerView"
    }

    init {
        orientation = VERTICAL
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        topBar = findViewById(R.id.vault_top_bar)
        chipAll = findViewById(R.id.chip_filter_all)
        chipRecent = findViewById(R.id.chip_filter_recent)
        chipFolders = findViewById(R.id.chip_filter_folders)
        btnSortToggle = findViewById(R.id.btn_sort_toggle)
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
            btnSortToggle.alpha = if (newSort == AccordionFolderAdapter.SortMode.TIME) 1f else 0.6f
        }

        // Close without lock: restores keyboard, keeps vault unlocked for remainder of session timer
        btnClose.setOnClickListener {
            closeWithoutLock()
        }

        // Close and lock: immediately locks vault session and restores keyboard
        btnLock.setOnClickListener {
            closeAndLock()
        }

        SecurityVaultDao.getInstance(context).addListener(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SecurityVaultDao.getInstance(context).addListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        SecurityVaultDao.getInstance(context).removeListener(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues())
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues())
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        super.onMeasure(widthSpec, heightSpec)
        setMeasuredDimension(width, height)
    }

    fun startExplorer(listener: KeyboardActionListener) {
        this.keyboardActionListener = listener
        applyThemeColors()
        updateChipHighlight(chipAll)
        loadData()
        visibility = View.VISIBLE
    }

    fun stopExplorer() {
        visibility = View.GONE
    }

    fun closeWithoutLock() {
        LogCatcher.log('I', TAG, "Closing Security Vault Explorer (retaining active unlock session)")
        stopExplorer()
        KeyboardSwitcher.getInstance().closeSecurityVaultExplorer(false)
    }

    fun closeAndLock() {
        LogCatcher.log('I', TAG, "Locking Security Vault immediately on user action")
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

        listOf(chipAll, chipRecent, chipFolders).forEach { chip ->
            if (chip == activeChip) {
                chip.setTextColor(textColor)
                chip.paint.isFakeBoldText = true
            } else {
                chip.setTextColor(hintColor)
                chip.paint.isFakeBoldText = false
            }
        }
    }

    private fun applyThemeColors() {
        val colors = runCatching { Settings.getValues().mColors }.getOrNull()
        if (colors != null) {
            colors.setBackground(this, ColorType.MAIN_BACKGROUND)
            colors.setBackground(topBar, ColorType.STRIP_BACKGROUND)
            colors.setColor(btnLock, ColorType.KEY_ICON)
            colors.setColor(btnClose, ColorType.KEY_ICON)
            colors.setColor(btnSortToggle, ColorType.KEY_ICON)

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
            emptyText.setTextColor(Color.WHITE)
        }
    }
}
