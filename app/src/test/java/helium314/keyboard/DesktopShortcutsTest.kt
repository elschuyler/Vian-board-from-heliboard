// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.os.Looper
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.desktop.DesktopShortcutsCatalog
import helium314.keyboard.keyboard.desktop.DesktopShortcutsView
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    shadows = [ShadowInputMethodManager2::class]
)
class DesktopShortcutsTest {

    private class RecordingInputConnection(
        view: View,
        private val sendKeyResult: Boolean = true,
        private val contextActionResult: Boolean = true
    ) : BaseInputConnection(view, false) {
        val sentEvents = mutableListOf<KeyEvent>()
        val contextActions = mutableListOf<Int>()

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            sentEvents.add(event)
            return sendKeyResult
        }

        override fun performContextMenuAction(id: Int): Boolean {
            contextActions.add(id)
            return contextActionResult
        }
    }

    private class RecordingActionListener : KeyboardActionListener.Adapter() {
        val codes = mutableListOf<Pair<Int, Boolean>>()

        override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
            codes.add(primaryCode to isKeyRepeat)
        }
    }

    @Test
    fun catalog_removesBrowserChromeAndIncludesEditorKeys() {
        val allIds = DesktopShortcutsCatalog.ALL_SHORTCUTS.map { it.id }.toSet()
        val removedIds = listOf("NEW_TAB", "CLOSE_TAB", "REFRESH", "BOOKMARK", "PRINT", "ZOOM_IN", "ZOOM_OUT")
        removedIds.forEach { removed ->
            assertFalse(allIds.contains(removed), "Browser shortcut $removed should be removed")
        }

        val byId = DesktopShortcutsCatalog.ALL_SHORTCUTS.associateBy { it.id }
        assertNotNull(byId["SAVE"])
        assertEquals("Editing", byId["SAVE"]?.category)
        assertNotNull(byId["ESCAPE"])
        assertEquals("Navigation", byId["ESCAPE"]?.category)

        assertNotNull(byId["TAB"])
        assertEquals(KeyEvent.KEYCODE_TAB, byId["TAB"]?.keyCode)
        assertFalse(byId["TAB"]!!.isShift)
        assertFalse(byId["TAB"]!!.isCtrl)

        assertNotNull(byId["OUTDENT"])
        assertEquals(KeyEvent.KEYCODE_TAB, byId["OUTDENT"]?.keyCode)
        assertTrue(byId["OUTDENT"]!!.isShift)
        assertFalse(byId["OUTDENT"]!!.isCtrl)

        assertNotNull(byId["COMMENT"])
        assertEquals(KeyEvent.KEYCODE_SLASH, byId["COMMENT"]?.keyCode)
        assertTrue(byId["COMMENT"]!!.isCtrl)

        assertNotNull(byId["SELECT_NEXT"])
        assertEquals(KeyEvent.KEYCODE_D, byId["SELECT_NEXT"]?.keyCode)
        assertTrue(byId["SELECT_NEXT"]!!.isCtrl)

        assertNotNull(byId["DELETE_LINE"])
        assertEquals(KeyEvent.KEYCODE_K, byId["DELETE_LINE"]?.keyCode)
        assertTrue(byId["DELETE_LINE"]!!.isCtrl)
        assertTrue(byId["DELETE_LINE"]!!.isShift)

        // Fixed broken shortcuts
        assertEquals("Ctrl + Shift + Z", byId["REDO"]?.subtitle)
        assertEquals(KeyEvent.KEYCODE_Z, byId["REDO"]?.keyCode)
        assertTrue(byId["REDO"]!!.isCtrl)
        assertTrue(byId["REDO"]!!.isShift)

        assertEquals("Shift + Alt + ↓", byId["DUPLICATE"]?.subtitle)
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, byId["DUPLICATE"]?.keyCode)
        assertTrue(byId["DUPLICATE"]!!.isShift)
        assertTrue(byId["DUPLICATE"]!!.isAlt)
        assertFalse(byId["DUPLICATE"]!!.isCtrl)
    }

    @Test
    fun persistence_allowsOneToSevenShortcutsWithoutBackfillAndHardCapsAtSeven() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        val prefs = ime.prefs()
        prefs.edit(commit = true) { clear() }

        // Default when unconfigured is 7 defaults
        assertEquals(7, DesktopShortcutsCatalog.getChosenShortcuts(prefs).size)

        // Saving only 2 active shortcuts returns strictly those 2 (no backfill to 7)
        val twoActive = DesktopShortcutsCatalog.ALL_SHORTCUTS.map { s ->
            s to (s.id == "TAB" || s.id == "COMMENT")
        }
        DesktopShortcutsCatalog.saveConfig(prefs, twoActive)
        val loadedTwo = DesktopShortcutsCatalog.getChosenShortcuts(prefs)
        assertEquals(listOf("TAB", "COMMENT"), loadedTwo.map { it.id })

        // Saving >7 enabled shortcuts hard-caps at MAX_MODAL_SLOTS (7)
        val nineIds = setOf("SELECT_ALL", "COPY", "PASTE", "UNDO", "REDO", "TAB", "OUTDENT", "COMMENT", "ESCAPE")
        val nineActive = DesktopShortcutsCatalog.ALL_SHORTCUTS.map { s -> s to nineIds.contains(s.id) }
        DesktopShortcutsCatalog.saveConfig(prefs, nineActive)
        assertEquals(7, DesktopShortcutsCatalog.getChosenShortcuts(prefs).size)
        assertEquals(7, DesktopShortcutsCatalog.getAllWithConfig(prefs).count { it.second })
    }

    @Test
    fun sendDesktopKeyEvent_usesVirtualDeviceFlagsAndDistinctTimestamps() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        val ic = RecordingInputConnection(View(ime), sendKeyResult = true)

        val sent = DesktopShortcutsCatalog.sendDesktopKeyEvent(
            ic = ic,
            keyCode = KeyEvent.KEYCODE_Z,
            metaState = DesktopShortcutsCatalog.buildMetaState(isCtrl = true, isShift = true)
        )

        assertTrue(sent)
        assertEquals(2, ic.sentEvents.size)
        val down = ic.sentEvents[0]
        val up = ic.sentEvents[1]
        assertEquals(KeyEvent.ACTION_DOWN, down.action)
        assertEquals(KeyEvent.ACTION_UP, up.action)
        assertEquals(KeyCharacterMap.VIRTUAL_KEYBOARD, down.deviceId)
        assertEquals(KeyCharacterMap.VIRTUAL_KEYBOARD, up.deviceId)
        val expectedFlags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        assertTrue((down.flags and expectedFlags) == expectedFlags)
        assertTrue((up.flags and expectedFlags) == expectedFlags)
        assertEquals(down.downTime, up.downTime)
        assertTrue(up.eventTime > down.eventTime)
        assertTrue(down.isCtrlPressed && down.isShiftPressed)
    }

    @Test
    fun executeShortcut_nonDuplicatingDispatchAndFallbacks() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        val byId = DesktopShortcutsCatalog.ALL_SHORTCUTS.associateBy { it.id }

        // COPY when context action succeeds -> NO duplicate Ctrl+C key event
        val icContextOk = RecordingInputConnection(View(ime), sendKeyResult = true, contextActionResult = true)
        DesktopShortcutsCatalog.executeShortcut(ime, byId.getValue("COPY"), icContextOk, null)
        assertEquals(listOf(android.R.id.copy), icContextOk.contextActions)
        assertTrue(icContextOk.sentEvents.isEmpty(), "COPY should not fire duplicate KeyEvent when context action succeeds")

        // COPY when context action returns false -> falls back to Ctrl+C KeyEvent
        val icContextFail = RecordingInputConnection(View(ime), sendKeyResult = true, contextActionResult = false)
        DesktopShortcutsCatalog.executeShortcut(ime, byId.getValue("COPY"), icContextFail, null)
        assertEquals(listOf(android.R.id.copy), icContextFail.contextActions)
        assertEquals(2, icContextFail.sentEvents.size)
        assertEquals(KeyEvent.KEYCODE_C, icContextFail.sentEvents[0].keyCode)
        assertTrue(icContextFail.sentEvents[0].isCtrlPressed)

        // REDO when sendKeyEvent succeeds -> sends Ctrl+Shift+Z, NO duplicate context action
        val icRedoOk = RecordingInputConnection(View(ime), sendKeyResult = true, contextActionResult = true)
        DesktopShortcutsCatalog.executeShortcut(ime, byId.getValue("REDO"), icRedoOk, null)
        assertEquals(2, icRedoOk.sentEvents.size)
        assertEquals(KeyEvent.KEYCODE_Z, icRedoOk.sentEvents[0].keyCode)
        assertTrue(icRedoOk.sentEvents[0].isCtrlPressed && icRedoOk.sentEvents[0].isShiftPressed)
        assertTrue(icRedoOk.contextActions.isEmpty(), "REDO should not fire duplicate performContextMenuAction")

        // REDO when sendKeyEvent fails -> falls back to performContextMenuAction(redo)
        val icRedoFail = RecordingInputConnection(View(ime), sendKeyResult = false, contextActionResult = true)
        DesktopShortcutsCatalog.executeShortcut(ime, byId.getValue("REDO"), icRedoFail, null)
        assertEquals(listOf(android.R.id.redo), icRedoFail.contextActions)

        // SELECT_WORD -> routes to CLIPBOARD_SELECT_WORD and NEVER sends Ctrl+W (Close Tab)
        val listener = RecordingActionListener()
        val icWord = RecordingInputConnection(View(ime), sendKeyResult = true, contextActionResult = true)
        DesktopShortcutsCatalog.executeShortcut(ime, byId.getValue("SELECT_WORD"), icWord, listener)
        assertEquals(listOf(KeyCode.CLIPBOARD_SELECT_WORD to false), listener.codes)
        assertTrue(icWord.sentEvents.isEmpty(), "SELECT_WORD must never emit Ctrl+W")

        // SELECT_LINE -> unshifted Home then Shift+End (no Ctrl+L address bar conflict)
        val icLine = RecordingInputConnection(View(ime))
        DesktopShortcutsCatalog.executeShortcut(ime, byId.getValue("SELECT_LINE"), icLine, null)
        assertEquals(4, icLine.sentEvents.size)
        assertEquals(KeyEvent.KEYCODE_MOVE_HOME, icLine.sentEvents[0].keyCode)
        assertFalse(icLine.sentEvents[0].isShiftPressed)
        assertEquals(KeyEvent.KEYCODE_MOVE_END, icLine.sentEvents[2].keyCode)
        assertTrue(icLine.sentEvents[2].isShiftPressed)
        assertFalse(icLine.sentEvents[2].isCtrlPressed)
    }

    @Test
    fun desktopShortcutsView_liveProvidersOption2BottomRowCollapseAndArrowRepeat() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        val prefs = ime.prefs()
        DesktopShortcutsCatalog.saveConfig(
            prefs,
            DesktopShortcutsCatalog.ALL_SHORTCUTS.map { it to (it.id == "TAB" || it.id == "ESCAPE") }
        )

        val themedContext = KeyboardTheme.getKeyboardTheme(ime).let {
            android.view.ContextThemeWrapper(ime, it.mStyleId)
        }
        val view = LayoutInflater.from(themedContext)
            .inflate(R.layout.desktop_shortcuts_view, null) as DesktopShortcutsView

        val listener = RecordingActionListener()
        val ic1 = RecordingInputConnection(View(ime), sendKeyResult = true, contextActionResult = true)
        val ic2 = RecordingInputConnection(View(ime), sendKeyResult = true, contextActionResult = true)
        val icFail = RecordingInputConnection(View(ime), sendKeyResult = false, contextActionResult = false)

        var activeIc: InputConnection? = ic1
        val activeEditorInfo = EditorInfo()
        var flushCount = 0

        view.startDesktopShortcuts(
            actionListener = listener,
            keyVisualAttributes = null,
            editorInfo = activeEditorInfo,
            inputConnectionProvider = { activeIc },
            editorInfoProvider = { activeEditorInfo },
            flushComposingAction = { flushCount++ }
        )

        // Row collapse for 2 active shortcuts: row1 visible (2), row2 & row3 GONE
        val row1 = view.findViewById<LinearLayout>(R.id.fat_row_1)
        val row2 = view.findViewById<LinearLayout>(R.id.fat_row_2)
        val row3 = view.findViewById<LinearLayout>(R.id.fat_row_3)
        assertEquals(View.VISIBLE, row1.visibility)
        assertEquals(2, row1.childCount)
        assertEquals(View.GONE, row2.visibility)
        assertEquals(View.GONE, row3.visibility)

        // Dynamic live InputConnection switch + bottom row DELETE / SPACE / ENTER
        activeIc = ic2
        view.dispatchBottomRowCodeForTesting(KeyCode.DELETE)
        view.dispatchBottomRowCodeForTesting(Constants.CODE_SPACE)
        view.dispatchBottomRowCodeForTesting(Constants.CODE_ENTER) // UNSPECIFIED -> KEYCODE_ENTER
        assertEquals(3, flushCount)
        assertTrue(ic1.sentEvents.isEmpty(), "Stale InputConnection must not receive events")
        assertEquals(
            listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER),
            ic2.sentEvents.filter { it.action == KeyEvent.ACTION_DOWN }.map { it.keyCode }
        )
        assertTrue(listener.codes.isEmpty(), "Fallback listener must not fire when live IC succeeds")

        // Fallback when sendKeyEvent returns false
        activeIc = icFail
        view.dispatchBottomRowCodeForTesting(KeyCode.DELETE)
        assertEquals(listOf(KeyCode.DELETE to false), listener.codes)

        // Explicit IME_ACTION_SEARCH routes ENTER to original action listener (Find bar)
        activeIc = ic2
        activeEditorInfo.imeOptions = EditorInfo.IME_ACTION_SEARCH
        val countBeforeEnter = ic2.sentEvents.size
        view.dispatchBottomRowCodeForTesting(Constants.CODE_ENTER)
        assertEquals(countBeforeEnter, ic2.sentEvents.size, "Search action Enter should invoke IME action")
        assertEquals(Constants.CODE_ENTER to false, listener.codes.last())

        // Toolbar REDO uses Ctrl+Shift+Z and SELECT_WORD uses CLIPBOARD_SELECT_WORD
        val countBeforeRedo = ic2.sentEvents.size
        view.handleToolbarClick(ToolbarKey.REDO)
        assertEquals(KeyEvent.KEYCODE_Z, ic2.sentEvents[countBeforeRedo].keyCode)
        assertTrue(ic2.sentEvents[countBeforeRedo].isCtrlPressed && ic2.sentEvents[countBeforeRedo].isShiftPressed)

        view.handleToolbarClick(ToolbarKey.SELECT_WORD)
        assertEquals(KeyCode.CLIPBOARD_SELECT_WORD to false, listener.codes.last())

        // Arrow hold-to-repeat on Up button (index 1 of arrow_row_1)
        val arrowRow1 = view.findViewById<LinearLayout>(R.id.arrow_row_1)
        val upButton = arrowRow1.getChildAt(1)
        val eventsBeforeArrow = ic2.sentEvents.size
        val downEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        upButton.dispatchTouchEvent(downEvent)
        downEvent.recycle()
        // Immediate step on ACTION_DOWN (2 KeyEvents: down+up)
        assertEquals(eventsBeforeArrow + 2, ic2.sentEvents.size)

        // Advance past 400ms initial delay + two 50ms repeat intervals (~510ms total)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(510, TimeUnit.MILLISECONDS)
        val downKeyCount = ic2.sentEvents.drop(eventsBeforeArrow).count {
            it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_DPAD_UP
        }
        assertTrue(downKeyCount >= 3, "Expected initial press + repeats on hold, got $downKeyCount")

        // Release & stopDesktopShortcuts halts any further repeats
        val upEvent = MotionEvent.obtain(0L, 520L, MotionEvent.ACTION_UP, 10f, 10f, 0)
        upButton.dispatchTouchEvent(upEvent)
        upEvent.recycle()
        val countAfterRelease = ic2.sentEvents.size
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        assertEquals(countAfterRelease, ic2.sentEvents.size, "No repeat events may fire after ACTION_UP")
    }

    @Test
    fun createFatButton_setsAccessibleContentDescription() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        val themedContext = KeyboardTheme.getKeyboardTheme(ime).let {
            android.view.ContextThemeWrapper(ime, it.mStyleId)
        }
        val view = LayoutInflater.from(themedContext)
            .inflate(R.layout.desktop_shortcuts_view, null) as DesktopShortcutsView
        view.startDesktopShortcuts(KeyboardActionListener.EMPTY_LISTENER, null, EditorInfo(), null)
        val firstBtn = view.findViewById<LinearLayout>(R.id.fat_row_1).getChildAt(0) as LinearLayout
        val title = (firstBtn.getChildAt(0) as TextView).text
        assertTrue(firstBtn.contentDescription?.contains(title) == true)
    }
}
