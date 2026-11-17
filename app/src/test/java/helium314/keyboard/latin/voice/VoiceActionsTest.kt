// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.database.VoiceReplacementDao
import helium314.keyboard.latin.utils.prefs
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceActionsTest {

    private lateinit var context: Context
    private lateinit var dao: VoiceReplacementDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dao = VoiceReplacementDao.getInstance(context)
        dao.clear()
        VoiceActionCatalog.resetDefaults(context.prefs())
    }

    @After
    fun tearDown() {
        dao.clear()
        VoiceActionCatalog.resetDefaults(context.prefs())
    }

    @Test
    fun `word improvement matches spelled letters with punctuation and longest rule first`() {
        // Add shorter rule AFTER longer rule to verify longest-first priority overrides timestamp order
        dao.addOrUpdate("s k p", "skp", isWholeWord = true)
        dao.addOrUpdate("p", "purl", isWholeWord = true)

        assertEquals("skp", dao.applyReplacements("s k p"))
        assertEquals("skp", dao.applyReplacements("S, K, P"))
        assertEquals("skp.", dao.applyReplacements("S. K. P."))
        assertEquals("skp purl", dao.applyReplacements("s-k-p p"))
    }

    @Test
    fun `word improvement safely handles dollar and backslash characters`() {
        dao.addOrUpdate("five bucks", "$5", isWholeWord = true)
        dao.addOrUpdate("back slash", "\\", isWholeWord = true)

        assertEquals("costs $5 and \\", dao.applyReplacements("costs five bucks and back slash"))
    }

    @Test
    fun `word improvement protects replaced spans from downstream voice actions`() {
        // Custom rule outputs literal "star stitch"; toggling STAR ON must not rewrite it into "* stitch"
        dao.addOrUpdate("star stitch", "star stitch", isWholeWord = true)
        VoiceActionCatalog.toggleActionEnabled(context.prefs(), VoiceActionCatalog.ID_STAR)

        val result = VoiceActionProcessor.processText(context, "star stitch star")
        assertEquals("star stitch *", result)
    }

    @Test
    fun `default voice action slots match expected states`() {
        val slots = VoiceActionCatalog.getSlottedActions(context.prefs())
        assertEquals(5, slots.size)
        assertEquals(listOf("↵", "( )", "*", "×", "123"), slots.map { it.symbol })
        assertTrue(slots[0].isEnabled)  // Next line
        assertTrue(slots[1].isEnabled)  // Brackets
        assertFalse(slots[2].isEnabled) // Star OFF by default
        assertTrue(slots[3].isEnabled)  // Multiply
        assertTrue(slots[4].isEnabled)  // Spoken numbers ON by default
    }

    @Test
    fun `spoken numbers converts cardinals accurately and keeps separate knitting digits distinct`() {
        assertEquals("knit 24, purl 8", VoiceActionProcessor.processText(context, "knit twenty-four, purl eight"))
        assertEquals("row 2 4", VoiceActionProcessor.processText(context, "row two four"))
        assertEquals("105 stitches", VoiceActionProcessor.processText(context, "one hundred and five stitches"))
        assertEquals("100 and purl", VoiceActionProcessor.processText(context, "one hundred and purl"))
        assertEquals("wait a second", VoiceActionProcessor.processText(context, "wait a second"))
    }

    @Test
    fun `multiply turns number times into 2x without false positives`() {
        assertEquals("2x", VoiceActionProcessor.processText(context, "two times"))
        assertEquals("2x", VoiceActionProcessor.processText(context, "2 times"))
        assertEquals("repeat 24x", VoiceActionProcessor.processText(context, "repeat twenty four times"))
        assertEquals("many times before", VoiceActionProcessor.processText(context, "many times before"))

        // Still works for "two times" even when Spoken Numbers toggle is OFF
        VoiceActionCatalog.toggleActionEnabled(context.prefs(), VoiceActionCatalog.ID_NUMBERS)
        assertEquals("repeat 2x three rows", VoiceActionProcessor.processText(context, "repeat two times three rows"))
    }

    @Test
    fun `star is off by default and converts when toggled on`() {
        assertEquals("5 star yarn", VoiceActionProcessor.processText(context, "five star yarn"))

        val nowEnabled = VoiceActionCatalog.toggleActionEnabled(context.prefs(), VoiceActionCatalog.ID_STAR)
        assertTrue(nowEnabled)
        assertEquals("* k 2 *", VoiceActionProcessor.processText(context, "star k two star"))
    }

    @Test
    fun `custom open close wrapper works when slotted and edited`() {
        val prefs = context.prefs()
        val existing = VoiceActionCatalog.getAll(prefs).toMutableList()
        val starIdx = existing.indexOfFirst { it.id == VoiceActionCatalog.ID_STAR }
        existing[starIdx] = existing[starIdx].copy(isSlotted = false)

        val squareBrackets = VoiceActionItem(
            id = VoiceActionCatalog.createCustomId(),
            title = "Square Brackets",
            symbol = "[ ]",
            kind = VoiceActionKind.CUSTOM_WRAPPER,
            triggerPhrases = "open square",
            closeTriggerPhrases = "close square",
            outputText = "[",
            closeOutputText = "]",
            isEnabled = true,
            isSlotted = true
        )
        existing.add(squareBrackets)
        VoiceActionCatalog.saveAll(prefs, existing)

        val output = VoiceActionProcessor.processText(context, "open square k two tog, two times close square")
        assertEquals("[k 2 tog, 2x]", output)
    }

    @Test
    fun `brackets and next line format knitting patterns cleanly`() {
        dao.addOrUpdate("s k p", "skp", isWholeWord = true)
        val input = "Row one, next line. Repeat open bracket s, k, p, two times close bracket."
        val output = VoiceActionProcessor.processText(context, input)
        assertEquals("Row 1\nRepeat (skp, 2x).", output)
    }

    @Test
    fun `custom CRUD actions and 5-slot cap persist properly`() {
        val prefs = context.prefs()
        val existing = VoiceActionCatalog.getAll(prefs).toMutableList()
        // Unslot STAR to free one of the 5 slots for a custom tight "/" action
        val starIdx = existing.indexOfFirst { it.id == VoiceActionCatalog.ID_STAR }
        existing[starIdx] = existing[starIdx].copy(isSlotted = false)

        val customSlash = VoiceActionItem(
            id = VoiceActionCatalog.createCustomId(),
            title = "Slash",
            symbol = "/",
            kind = VoiceActionKind.CUSTOM_SYMBOL,
            triggerPhrases = "slash, forward slash",
            outputText = "/",
            spacing = VoiceActionSpacing.TIGHT,
            isEnabled = true,
            isSlotted = true
        )
        val extraShouldBeUnslotted = VoiceActionItem(
            id = VoiceActionCatalog.createCustomId(),
            title = "Percent",
            symbol = "%",
            kind = VoiceActionKind.CUSTOM_SYMBOL,
            triggerPhrases = "percent",
            outputText = "%",
            spacing = VoiceActionSpacing.ATTACH_LEFT,
            isEnabled = true,
            isSlotted = true
        )
        existing.add(customSlash)
        existing.add(extraShouldBeUnslotted)

        VoiceActionCatalog.saveAll(prefs, existing)

        val slotted = VoiceActionCatalog.getSlottedActions(prefs)
        assertEquals(5, slotted.size)
        assertTrue(slotted.any { it.id == customSlash.id })
        assertFalse(slotted.any { it.id == extraShouldBeUnslotted.id })

        // Tight slash formats "two slash four" -> "2/4" and unslotted "%" stays inactive
        assertEquals("2/4 50 percent", VoiceActionProcessor.processText(context, "two slash four fifty percent"))
    }
}
