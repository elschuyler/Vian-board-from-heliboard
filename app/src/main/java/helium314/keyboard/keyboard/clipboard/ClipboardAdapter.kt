// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.database.PromptDao
import helium314.keyboard.latin.database.PromptEntry
import helium314.keyboard.latin.settings.Settings

enum class HistoryMode {
    CLIPBOARD,
    PROMPTS
}

class ClipboardAdapter(
       val clipboardLayoutParams: ClipboardLayoutParams,
       val keyEventListener: OnKeyEventListener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_CLIPBOARD_ITEM = 0
        const val VIEW_TYPE_PROMPT_ADD = 1
        const val VIEW_TYPE_PROMPT_ITEM = 2
    }

    var mode: HistoryMode = HistoryMode.CLIPBOARD
    var clipboardHistoryManager: ClipboardHistoryManager? = null
    var promptDao: PromptDao? = null
    var onPromptSelect: ((String) -> Unit)? = null

    var pinnedIconResId = 0
    var itemBackgroundId = 0
    var itemTypeFace: Typeface? = null
    var itemTextColor = 0
    var itemTextSize = 0f

    override fun getItemViewType(position: Int): Int {
        return if (mode == HistoryMode.PROMPTS) {
            if (position == 0) VIEW_TYPE_PROMPT_ADD else VIEW_TYPE_PROMPT_ITEM
        } else {
            VIEW_TYPE_CLIPBOARD_ITEM
        }
    }

    override fun getItemCount(): Int {
        return if (mode == HistoryMode.PROMPTS) {
            (promptDao?.count ?: 0) + 1
        } else {
            clipboardHistoryManager?.getHistorySize() ?: 0
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_PROMPT_ADD -> {
                val view = inflater.inflate(R.layout.prompt_entry_add_card, parent, false)
                PromptAddViewHolder(view)
            }
            VIEW_TYPE_PROMPT_ITEM -> {
                val view = inflater.inflate(R.layout.prompt_entry_key, parent, false)
                PromptItemViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.clipboard_entry_key, parent, false)
                ClipboardViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is PromptAddViewHolder -> holder.bind()
            is PromptItemViewHolder -> {
                val entry = promptDao?.getEntry(position - 1) ?: return
                holder.bind(entry)
            }
            is ClipboardViewHolder -> {
                holder.setContent(getClipItem(position))
            }
        }
    }

    private fun getClipItem(position: Int) = clipboardHistoryManager?.getHistoryEntry(position)

    // --- CLIPBOARD VIEW HOLDER ---
    inner class ClipboardViewHolder(
            view: View
    ) : RecyclerView.ViewHolder(view), View.OnClickListener, View.OnTouchListener, View.OnLongClickListener {

        private val pinnedIconView: ImageView
        private val contentTextView: TextView
        private val contentImageView: ImageView

        init {
            view.apply {
                setOnClickListener(this@ClipboardViewHolder)
                setOnTouchListener(this@ClipboardViewHolder)
                setOnLongClickListener(this@ClipboardViewHolder)
                if (itemBackgroundId != 0) {
                    setBackgroundResource(itemBackgroundId)
                }
                isHapticFeedbackEnabled = false
            }
            Settings.getValues().mColors.setBackground(view, ColorType.KEY_BACKGROUND)
            pinnedIconView = view.findViewById<ImageView>(R.id.clipboard_entry_pinned_icon).apply {
                visibility = View.GONE
                if (pinnedIconResId != 0) {
                    setImageResource(pinnedIconResId)
                }
            }
            contentTextView = view.findViewById<TextView>(R.id.clipboard_entry_text_content).apply {
                typeface = itemTypeFace
                setTextColor(itemTextColor)
                if (itemTextSize > 0f) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
                }
            }
            contentImageView = view.findViewById(R.id.clipboard_entry_image_content)
            clipboardLayoutParams.setItemProperties(view)
            val colors = Settings.getValues().mColors
            colors.setColor(pinnedIconView, ColorType.CLIPBOARD_PIN)
        }

        fun setContent(historyEntry: ClipboardHistoryEntry?) {
            if (historyEntry == null) return
            itemView.tag = historyEntry.id
            if (historyEntry.filename != null) {
                historyEntry.setImageAndDescription(contentImageView, contentTextView)
            } else {
                contentTextView.text = historyEntry.text?.take(1000) // truncate displayed text for performance reasons
            }
            pinnedIconView.visibility = if (historyEntry.isPinned) View.VISIBLE else View.GONE
            contentImageView.visibility = if (historyEntry.filename != null) View.VISIBLE else View.GONE
            contentTextView.visibility = if (contentTextView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                keyEventListener.onKeyDown(view.tag as Long)
            }
            return false
        }

        override fun onClick(view: View) {
            keyEventListener.onKeyUp(view.tag as Long)
        }

        override fun onLongClick(view: View): Boolean {
            val clipId = view.tag as? Long ?: return false
            val entry = clipboardHistoryManager?.getHistoryEntryContent(clipId) ?: return false
            showCompactActionMenu(view, clipId, entry)
            return true
        }

        private fun showCompactActionMenu(anchorView: View, clipId: Long, entry: ClipboardHistoryEntry) {
            val context = anchorView.context
            val popupView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = (6 * context.resources.displayMetrics.density).toInt()
                val padV = (2 * context.resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
            }
            Settings.getValues().mColors.setBackground(popupView, ColorType.KEY_BACKGROUND)

            val popupWindow = PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                elevation = 16f
                isOutsideTouchable = true
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }

            val size = (44 * context.resources.displayMetrics.density).toInt()
            val btnParams = LinearLayout.LayoutParams(size, size).apply {
                val m = (2 * context.resources.displayMetrics.density).toInt()
                setMargins(m, 0, m, 0)
            }

            // 1. Pin / Unpin Button
            val pinButton = ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                layoutParams = btnParams
                setImageResource(R.drawable.ic_clipboard_pin_rounded)
                contentDescription = if (entry.isPinned) "Unpin" else "Pin"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                Settings.getValues().mColors.setColor(this, ColorType.CLIPBOARD_PIN)
                setOnClickListener {
                    popupWindow.dismiss()
                    clipboardHistoryManager?.toggleClipPinned(clipId)
                }
            }
            popupView.addView(pinButton)

            // 2. Save to Quick Notes / Prompt List
            if (entry.filename == null && !entry.text.isNullOrEmpty()) {
                val saveToPromptButton = ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                    layoutParams = btnParams
                    setImageResource(R.drawable.ic_plus)
                    contentDescription = "Save to Quick Notes"
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    Settings.getValues().mColors.setColor(this, ColorType.TOOL_BAR_KEY)
                    setOnClickListener {
                        popupWindow.dismiss()
                        val clipText = entry.text
                        if (!clipText.isNullOrEmpty()) {
                            PromptDao.getInstance(context).addPrompt(clipText)
                            Toast.makeText(context, "Saved to Quick Notes", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                popupView.addView(saveToPromptButton)
            }

            // 3. Delete Button
            val deleteButton = ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                layoutParams = btnParams
                setImageResource(R.drawable.ic_bin_rounded)
                contentDescription = "Delete"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                Settings.getValues().mColors.setColor(this, ColorType.TOOL_BAR_KEY)
                setOnClickListener {
                    popupWindow.dismiss()
                    val pos = absoluteAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        if (entry.isPinned) {
                            clipboardHistoryManager?.toggleClipPinned(clipId)
                        }
                        clipboardHistoryManager?.removeEntry(pos)
                    }
                }
            }
            popupView.addView(deleteButton)

            popupView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val popupWidth = popupView.measuredWidth
            val popupHeight = popupView.measuredHeight
            val xOff = (anchorView.width - popupWidth) / 2
            val yOff = -(anchorView.height + popupHeight + (4 * context.resources.displayMetrics.density).toInt())
            popupWindow.showAsDropDown(anchorView, xOff, yOff)
        }
    }

    // --- PROMPT ADD VIEW HOLDER ---
    inner class PromptAddViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener {
        private val addIcon: ImageView = itemView.findViewById(R.id.prompt_add_icon)

        init {
            itemView.setOnClickListener(this)
            itemView.isHapticFeedbackEnabled = false
            if (itemBackgroundId != 0) {
                itemView.setBackgroundResource(itemBackgroundId)
            }
            Settings.getValues().mColors.setBackground(itemView, ColorType.KEY_BACKGROUND)
            Settings.getValues().mColors.setColor(addIcon, ColorType.TOOL_BAR_KEY)
        }

        fun bind() {
            clipboardLayoutParams.setItemProperties(itemView)
        }

        override fun onClick(v: View) {
            showAddDialog(v)
        }

        private fun showAddDialog(anchorView: View) {
            val context = anchorView.context
            val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * context.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            val titleText = TextView(context).apply {
                text = "Add Prompt / Note"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, (12 * context.resources.displayMetrics.density).toInt())
            }
            layout.addView(titleText)

            val editText = EditText(context).apply {
                hint = "Type your prompt or note here…"
                minLines = 5
                maxLines = 15
                gravity = Gravity.TOP or Gravity.START
                val bgPad = (10 * context.resources.displayMetrics.density).toInt()
                setPadding(bgPad, bgPad, bgPad, bgPad)
                setBackgroundResource(android.R.drawable.editbox_background_normal)
            }
            layout.addView(editText)

            val buttonRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, (16 * context.resources.displayMetrics.density).toInt(), 0, 0)
            }

            val cancelButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Cancel"
                setOnClickListener { dialog.dismiss() }
            }

            val saveButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Save"
                setOnClickListener {
                    val newText = editText.text.toString().trim()
                    if (newText.isNotEmpty()) {
                        promptDao?.addPrompt(newText)
                    }
                    dialog.dismiss()
                }
            }

            buttonRow.addView(cancelButton)
            buttonRow.addView(saveButton)
            layout.addView(buttonRow)

            dialog.setContentView(layout)

            val window = dialog.window
            if (window != null) {
                val lp = window.attributes
                lp.token = anchorView.rootView.windowToken ?: anchorView.windowToken
                lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
                window.attributes = lp
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }

            dialog.show()
            editText.requestFocus()
        }
    }

    // --- PROMPT ITEM VIEW HOLDER ---
    inner class PromptItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener, View.OnLongClickListener {
        private val titleView: TextView = itemView.findViewById(R.id.clipboard_entry_text_content)
        private val pinnedIcon: ImageView = itemView.findViewById(R.id.clipboard_entry_pinned_icon)
        private val imageView: ImageView = itemView.findViewById(R.id.clipboard_entry_image_content)

        init {
            itemView.apply {
                setOnClickListener(this@PromptItemViewHolder)
                setOnLongClickListener(this@PromptItemViewHolder)
                isHapticFeedbackEnabled = false
                if (itemBackgroundId != 0) {
                    setBackgroundResource(itemBackgroundId)
                }
            }
            Settings.getValues().mColors.setBackground(itemView, ColorType.KEY_BACKGROUND)
            imageView.visibility = View.GONE
            if (pinnedIconResId != 0) {
                pinnedIcon.setImageResource(pinnedIconResId)
            }
            Settings.getValues().mColors.setColor(pinnedIcon, ColorType.CLIPBOARD_PIN)
            titleView.apply {
                typeface = itemTypeFace
                setTextColor(itemTextColor)
                if (itemTextSize > 0f) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
                }
                maxLines = 2
            }
        }

        fun bind(entry: PromptEntry) {
            itemView.tag = entry.id
            titleView.text = entry.text
            pinnedIcon.visibility = if (entry.isPinned) View.VISIBLE else View.GONE
            clipboardLayoutParams.setItemProperties(itemView)
        }

        override fun onClick(view: View) {
            val id = view.tag as? Long ?: return
            val entry = promptDao?.getEntryContent(id) ?: return
            onPromptSelect?.invoke(entry.text)
        }

        override fun onLongClick(view: View): Boolean {
            val id = view.tag as? Long ?: return false
            val entry = promptDao?.getEntryContent(id) ?: return false
            showCompactActionMenu(view, entry)
            return true
        }

        private fun showCompactActionMenu(anchorView: View, entry: PromptEntry) {
            val context = anchorView.context
            val popupView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = (6 * context.resources.displayMetrics.density).toInt()
                val padV = (2 * context.resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
            }
            Settings.getValues().mColors.setBackground(popupView, ColorType.KEY_BACKGROUND)

            val popupWindow = PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                elevation = 16f
                isOutsideTouchable = true
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }

            val size = (44 * context.resources.displayMetrics.density).toInt()
            val btnParams = LinearLayout.LayoutParams(size, size).apply {
                val m = (2 * context.resources.displayMetrics.density).toInt()
                setMargins(m, 0, m, 0)
            }

            // 1. Pin / Unpin Button
            val pinButton = ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                layoutParams = btnParams
                setImageResource(R.drawable.ic_clipboard_pin_rounded)
                contentDescription = if (entry.isPinned) "Unpin" else "Pin"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                Settings.getValues().mColors.setColor(this, ColorType.CLIPBOARD_PIN)
                setOnClickListener {
                    popupWindow.dismiss()
                    promptDao?.togglePinned(entry.id)
                }
            }
            popupView.addView(pinButton)

            // 2. Edit Button
            val editButton = ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                layoutParams = btnParams
                setImageResource(R.drawable.ic_edit)
                contentDescription = "Edit"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                Settings.getValues().mColors.setColor(this, ColorType.TOOL_BAR_KEY)
                setOnClickListener {
                    popupWindow.dismiss()
                    showEditDialog(anchorView, entry)
                }
            }
            popupView.addView(editButton)

            // 3. Delete Button
            val deleteButton = ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                layoutParams = btnParams
                setImageResource(R.drawable.ic_bin_rounded)
                contentDescription = "Delete"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                Settings.getValues().mColors.setColor(this, ColorType.TOOL_BAR_KEY)
                setOnClickListener {
                    popupWindow.dismiss()
                    val pos = absoluteAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos > 0) {
                        promptDao?.removeEntry(pos - 1)
                    }
                }
            }
            popupView.addView(deleteButton)

            popupView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val popupWidth = popupView.measuredWidth
            val popupHeight = popupView.measuredHeight
            val xOff = (anchorView.width - popupWidth) / 2
            val yOff = -(anchorView.height + popupHeight + (4 * context.resources.displayMetrics.density).toInt())
            popupWindow.showAsDropDown(anchorView, xOff, yOff)
        }

        private fun showEditDialog(anchorView: View, entry: PromptEntry) {
            val context = anchorView.context
            val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * context.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            val titleText = TextView(context).apply {
                text = "Edit Prompt / Note"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, (12 * context.resources.displayMetrics.density).toInt())
            }
            layout.addView(titleText)

            val editText = EditText(context).apply {
                setText(entry.text)
                setSelection(text.length)
                minLines = 5
                maxLines = 15
                gravity = Gravity.TOP or Gravity.START
                val bgPad = (10 * context.resources.displayMetrics.density).toInt()
                setPadding(bgPad, bgPad, bgPad, bgPad)
                setBackgroundResource(android.R.drawable.editbox_background_normal)
            }
            layout.addView(editText)

            val buttonRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, (16 * context.resources.displayMetrics.density).toInt(), 0, 0)
            }

            val cancelButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Cancel"
                setOnClickListener { dialog.dismiss() }
            }

            val saveButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Save"
                setOnClickListener {
                    val newText = editText.text.toString().trim()
                    if (newText.isNotEmpty()) {
                        promptDao?.updatePrompt(entry.id, newText)
                        notifyDataSetChanged()
                    }
                    dialog.dismiss()
                }
            }

            buttonRow.addView(cancelButton)
            buttonRow.addView(saveButton)
            layout.addView(buttonRow)

            dialog.setContentView(layout)

            val window = dialog.window
            if (window != null) {
                val lp = window.attributes
                lp.token = anchorView.rootView.windowToken ?: anchorView.windowToken
                lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
                window.attributes = lp
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }

            dialog.show()
            editText.requestFocus()
        }
    }
}

