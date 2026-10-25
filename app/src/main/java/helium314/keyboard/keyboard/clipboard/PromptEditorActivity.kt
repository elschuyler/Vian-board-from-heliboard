// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.database.PromptDao
import helium314.keyboard.latin.utils.LogCatcher

class PromptEditorActivity : ComponentActivity() {

    private var promptId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        promptId = intent.getLongExtra(EXTRA_PROMPT_ID, -1L)
        val initialTitle = intent.getStringExtra(EXTRA_PROMPT_TITLE) ?: ""
        val initialText = intent.getStringExtra(EXTRA_PROMPT_TEXT) ?: ""
        val isNew = promptId <= 0L

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // Root container with dark card styling
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            val bg = GradientDrawable().apply {
                setColor(0xFF23272A.toInt())
                cornerRadius = dp(16).toFloat()
            }
            background = bg
        }

        // Header Title
        val headerTitle = TextView(this).apply {
            text = if (isNew) "Add Prompt / Note" else "Edit Prompt / Note"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(14))
        }
        rootLayout.addView(headerTitle)

        // Title Input
        val titleInput = EditText(this).apply {
            hint = "Title (optional)"
            setHintTextColor(0xFF8E9297.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            isSingleLine = true
            maxLines = 1
            setText(initialTitle)
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                setColor(0xFF2F3136.toInt())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), 0xFF40444B.toInt())
            }
            background = bg
        }
        rootLayout.addView(titleInput)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        }
        rootLayout.addView(spacer)

        // Scrollable content area
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // Text Content Input
        val contentInput = EditText(this).apply {
            hint = "Type your prompt or note here…"
            setHintTextColor(0xFF8E9297.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            setText(initialText)
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                setColor(0xFF2F3136.toInt())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), 0xFF40444B.toInt())
            }
            background = bg
        }
        scrollView.addView(contentInput)
        rootLayout.addView(scrollView)

        // Button Bar (Cancel, Save)
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }

        val cancelButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            setTextColor(0xFFB9BBBE.toInt())
            setOnClickListener {
                closeAndRestorePromptKeyboard()
            }
        }

        val saveButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Save"
            setTextColor(0xFF5865F2.toInt())
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                val newText = contentInput.text.toString().trim()
                val newTitle = titleInput.text.toString().trim()
                if (newText.isNotEmpty()) {
                    val dao = PromptDao.getInstance(this@PromptEditorActivity)
                    if (promptId > 0L) {
                        dao.updatePrompt(promptId, newTitle, newText)
                        LogCatcher.i("PromptEditorActivity", "Updated prompt id=$promptId")
                    } else {
                        dao.addPrompt(newText, newTitle)
                        LogCatcher.i("PromptEditorActivity", "Added prompt")
                    }
                }
                closeAndRestorePromptKeyboard()
            }
        }

        buttonRow.addView(cancelButton)
        buttonRow.addView(saveButton)
        rootLayout.addView(buttonRow)

        setContentView(rootLayout)

        window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val target = if (initialTitle.isBlank() && isNew) titleInput else contentInput
        target.post {
            target.requestFocus()
            if (target.text.isNotEmpty()) {
                target.setSelection(target.text.length)
            }
        }
    }

    private fun closeAndRestorePromptKeyboard() {
        KeyboardSwitcher.getInstance()?.setPendingPromptLaunch(true)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onBackPressed() {
        closeAndRestorePromptKeyboard()
    }

    companion object {
        const val EXTRA_PROMPT_ID = "extra_prompt_id"
        const val EXTRA_PROMPT_TITLE = "extra_prompt_title"
        const val EXTRA_PROMPT_TEXT = "extra_prompt_text"

        fun start(context: Context, id: Long = -1L, title: String = "", text: String = "") {
            val intent = Intent(context, PromptEditorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_PROMPT_ID, id)
                putExtra(EXTRA_PROMPT_TITLE, title)
                putExtra(EXTRA_PROMPT_TEXT, text)
            }
            context.startActivity(intent)
        }
    }
}
