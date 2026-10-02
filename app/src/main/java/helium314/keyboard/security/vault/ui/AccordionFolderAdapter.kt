// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.security.vault.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.security.vault.data.VaultEntryEntity
import helium314.keyboard.security.vault.data.VaultGroupEntity

class AccordionFolderAdapter(
    private val context: Context,
    private val onEntryClicked: (VaultEntryEntity) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class FilterMode {
        ALL, RECENT, FOLDERS
    }

    enum class SortMode {
        NAME, TIME
    }

    sealed class ListItem {
        data class Folder(val group: VaultGroupEntity, val count: Int, var isExpanded: Boolean) : ListItem()
        data class Entry(val entry: VaultEntryEntity, val parentGroup: VaultGroupEntity?) : ListItem()
    }

    private var filterMode: FilterMode = FilterMode.ALL
    private var sortMode: SortMode = SortMode.NAME

    private var allGroups: List<VaultGroupEntity> = emptyList()
    private var allEntries: List<VaultEntryEntity> = emptyList()
    private var recentEntries: List<VaultEntryEntity> = emptyList()

    private val expandedGroupUuids = mutableSetOf<String>()
    private val displayItems = mutableListOf<ListItem>()

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_ENTRY = 1
    }

    fun setData(
        groups: List<VaultGroupEntity>,
        entries: List<VaultEntryEntity>,
        recent: List<VaultEntryEntity>
    ) {
        this.allGroups = groups
        this.allEntries = entries
        this.recentEntries = recent
        // By default expand root folders or the first group
        if (expandedGroupUuids.isEmpty() && groups.isNotEmpty()) {
            expandedGroupUuids.add(groups.first().groupUuid)
        }
        rebuildDisplayItems()
    }

    fun setFilterMode(mode: FilterMode) {
        if (this.filterMode != mode) {
            this.filterMode = mode
            rebuildDisplayItems()
        }
    }

    fun toggleSortMode(): SortMode {
        this.sortMode = if (this.sortMode == SortMode.NAME) SortMode.TIME else SortMode.NAME
        rebuildDisplayItems()
        return this.sortMode
    }

    private fun getRecursiveEntryCount(
        groupUuid: String,
        entriesByGroup: Map<String, List<VaultEntryEntity>>
    ): Int {
        var count = entriesByGroup[groupUuid]?.size ?: 0
        val directChildren = allGroups.filter { it.parentGroupUuid == groupUuid }
        for (child in directChildren) {
            count += getRecursiveEntryCount(child.groupUuid, entriesByGroup)
        }
        return count
    }

    private fun rebuildDisplayItems() {
        displayItems.clear()
        when (filterMode) {
            FilterMode.RECENT -> {
                val sortedRecent = if (sortMode == SortMode.NAME) {
                    recentEntries.sortedBy { it.title.lowercase() }
                } else {
                    recentEntries.sortedByDescending { it.updatedAt }
                }
                for (entry in sortedRecent) {
                    val parent = allGroups.find { it.groupUuid == entry.groupUuid }
                    displayItems.add(ListItem.Entry(entry, parent))
                }
            }
            FilterMode.ALL, FilterMode.FOLDERS -> {
                // Group entries by groupUuid
                val entriesByGroup = allEntries.groupBy { it.groupUuid }
                val sortedGroups = if (sortMode == SortMode.NAME) {
                    allGroups.sortedBy { it.name.lowercase() }
                } else {
                    allGroups
                }

                for (group in sortedGroups) {
                    val groupEntries = entriesByGroup[group.groupUuid].orEmpty()
                    val totalCount = getRecursiveEntryCount(group.groupUuid, entriesByGroup)
                    val isExpanded = expandedGroupUuids.contains(group.groupUuid) || filterMode == FilterMode.ALL
                    displayItems.add(ListItem.Folder(group, totalCount, isExpanded))

                    if (isExpanded) {
                        val sortedEntries = if (sortMode == SortMode.NAME) {
                            groupEntries.sortedBy { it.title.lowercase() }
                        } else {
                            groupEntries.sortedByDescending { it.updatedAt }
                        }
                        for (entry in sortedEntries) {
                            displayItems.add(ListItem.Entry(entry, group))
                        }
                    }
                }

                // Any orphan entries without group
                val orphanEntries = allEntries.filter { entry -> allGroups.none { it.groupUuid == entry.groupUuid } }
                if (orphanEntries.isNotEmpty()) {
                    val sortedOrphans = if (sortMode == SortMode.NAME) {
                        orphanEntries.sortedBy { it.title.lowercase() }
                    } else {
                        orphanEntries.sortedByDescending { it.updatedAt }
                    }
                    for (entry in sortedOrphans) {
                        displayItems.add(ListItem.Entry(entry, null))
                    }
                }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (displayItems[position]) {
            is ListItem.Folder -> TYPE_FOLDER
            is ListItem.Entry -> TYPE_ENTRY
        }
    }

    override fun getItemCount(): Int = displayItems.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER) {
            val v = inflater.inflate(R.layout.item_vault_folder, parent, false)
            FolderViewHolder(v)
        } else {
            val v = inflater.inflate(R.layout.item_vault_entry, parent, false)
            EntryViewHolder(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val colors = runCatching { Settings.getValues().mColors }.getOrNull()
        val textColor = colors?.get(ColorType.KEY_TEXT) ?: Color.WHITE
        val hintColor = colors?.get(ColorType.KEY_HINT_TEXT) ?: Color.LTGRAY
        val iconColor = colors?.get(ColorType.KEY_ICON) ?: Color.WHITE
        val cardBg = colors?.get(ColorType.KEY_BACKGROUND) ?: Color.parseColor("#333333")

        when (val item = displayItems[position]) {
            is ListItem.Folder -> {
                val vh = holder as FolderViewHolder
                vh.txtName.text = item.group.name
                vh.txtName.setTextColor(textColor)
                KeyboardTypeface.applyToTextView(vh.txtName)

                vh.txtArrow.text = if (item.isExpanded) "▼" else "▶"
                vh.txtArrow.setTextColor(hintColor)

                vh.txtCount.text = "${item.count} items"
                vh.txtCount.setTextColor(hintColor)

                colors?.setColor(vh.imgIcon, ColorType.KEY_ICON)

                vh.itemView.setOnClickListener {
                    if (expandedGroupUuids.contains(item.group.groupUuid)) {
                        expandedGroupUuids.remove(item.group.groupUuid)
                    } else {
                        expandedGroupUuids.add(item.group.groupUuid)
                    }
                    rebuildDisplayItems()
                }
            }
            is ListItem.Entry -> {
                val vh = holder as EntryViewHolder
                vh.txtTitle.text = item.entry.title
                vh.txtTitle.setTextColor(textColor)
                KeyboardTypeface.applyToTextView(vh.txtTitle)

                vh.txtUsername.text = if (!item.entry.username.isNullOrBlank()) item.entry.username else "No username"
                vh.txtUsername.setTextColor(hintColor)

                colors?.setColor(vh.imgIcon, ColorType.KEY_ICON)

                if (item.entry.totpSecretEncrypted != null) {
                    vh.totpCircle.visibility = View.VISIBLE
                    vh.totpCircle.setShowText(false)
                    vh.totpCircle.setProgress(1f, 30)
                } else {
                    vh.totpCircle.visibility = View.GONE
                }

                vh.itemView.setOnClickListener {
                    onEntryClicked(item.entry)
                }
            }
        }
    }

    class FolderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val txtArrow: TextView = v.findViewById(R.id.txt_folder_arrow)
        val imgIcon: ImageView = v.findViewById(R.id.img_folder_icon)
        val txtName: TextView = v.findViewById(R.id.txt_folder_name)
        val txtCount: TextView = v.findViewById(R.id.txt_folder_count)
    }

    class EntryViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val imgIcon: ImageView = v.findViewById(R.id.img_entry_icon)
        val txtTitle: TextView = v.findViewById(R.id.txt_entry_title)
        val txtUsername: TextView = v.findViewById(R.id.txt_entry_username)
        val totpCircle: TotpCircleProgressView = v.findViewById(R.id.totp_circle_preview)
    }
}
