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
import helium314.keyboard.latin.settings.Settings

class ClipboardAdapter(
       val clipboardLayoutParams: ClipboardLayoutParams,
       val keyEventListener: OnKeyEventListener
) : RecyclerView.Adapter<ClipboardAdapter.ViewHolder>() {

    var clipboardHistoryManager: ClipboardHistoryManager? = null

    var pinnedIconResId = 0
    var itemBackgroundId = 0
    var itemTypeFace: Typeface? = null
    var itemTextColor = 0
    var itemTextSize = 0f

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.clipboard_entry_key, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.setContent(getItem(position))
    }

    private fun getItem(position: Int) = clipboardHistoryManager?.getHistoryEntry(position)

    override fun getItemCount() = clipboardHistoryManager?.getHistorySize() ?: 0

    inner class ViewHolder(
            view: View
    ) : RecyclerView.ViewHolder(view), View.OnClickListener, View.OnTouchListener, View.OnLongClickListener {

        private val pinnedIconView: ImageView
        private val contentTextView: TextView
        private val contentImageView: ImageView

        init {
            view.apply {
                setOnClickListener(this@ViewHolder)
                setOnTouchListener(this@ViewHolder)
                setOnLongClickListener(this@ViewHolder)
                setBackgroundResource(itemBackgroundId)
                isHapticFeedbackEnabled = false
            }
            Settings.getValues().mColors.setBackground(view, ColorType.KEY_BACKGROUND)
            pinnedIconView = view.findViewById<ImageView>(R.id.clipboard_entry_pinned_icon).apply {
                visibility = View.GONE
                setImageResource(pinnedIconResId)
            }
            contentTextView = view.findViewById<TextView>(R.id.clipboard_entry_text_content).apply {
                typeface = itemTypeFace
                setTextColor(itemTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
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

            // 3. Edit Button (for text clips)
            if (entry.filename == null && !entry.text.isNullOrEmpty()) {
                val editButton = ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                    layoutParams = btnParams
                    setImageResource(R.drawable.ic_edit)
                    contentDescription = "Edit"
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    Settings.getValues().mColors.setColor(this, ColorType.TOOL_BAR_KEY)
                    setOnClickListener {
                        popupWindow.dismiss()
                        showEditDialog(anchorView, clipId, entry)
                    }
                }
                popupView.addView(editButton)
            }

            // 4. Delete Button
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

        private fun showEditDialog(anchorView: View, clipId: Long, entry: ClipboardHistoryEntry) {
            val context = anchorView.context
            val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * context.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            val titleText = TextView(context).apply {
                text = "Edit Clipboard Item"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, (12 * context.resources.displayMetrics.density).toInt())
            }
            layout.addView(titleText)

            val editText = EditText(context).apply {
                setText(entry.text ?: "")
                setSelection(text.length)
                minLines = 4
                maxLines = 12
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
                        clipboardHistoryManager?.updateClipText(clipId, newText)
                        notifyItemChanged(absoluteAdapterPosition)
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
