package macroforge


import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The word checks Claude's taps go through (text positions are normalized 0..1, like OCR's). */
class ClaudeSafetyTest {
    private fun line(text: String, x: Double, y: Double) = Ocr.Line(text, Region(x - 0.05, y - 0.02, 0.1, 0.04))

    @Test fun purchaseOffersAreRefusedAnywhere() {
        val v = ClaudeSafety.judge(listOf(line("Purchase for 500 Gems?", 0.5, 0.4), line("OK", 0.6, 0.7)), Point(0.6, 0.7))
        assertTrue(v is ClaudeSafety.Verdict.Refuse)
        assertTrue(ClaudeSafety.judge(listOf(line("Not enough Gems", 0.5, 0.4)), Point(0.1, 0.1)) is ClaudeSafety.Verdict.Refuse)
    }

    @Test fun irreversibleButtonsAskFirst() {
        val v = ClaudeSafety.judge(listOf(line("Sell", 0.8, 0.85), line("Back", 0.1, 0.9)), Point(0.8, 0.85))
        assertEquals(ClaudeSafety.Verdict.Confirm("Sell"), v)
        // the same screen, tapping Back instead, is fine
        assertEquals(ClaudeSafety.Verdict.Ok, ClaudeSafety.judge(listOf(line("Sell", 0.8, 0.85), line("Back", 0.1, 0.9)), Point(0.1, 0.9)))
    }

    @Test fun premiumCurrencyOnScreenAsksFirst() {
        assertTrue(ClaudeSafety.judge(listOf(line("Gem x50", 0.5, 0.3)), Point(0.5, 0.8)) is ClaudeSafety.Verdict.Confirm)
    }

    @Test fun premiumCurrencyAsARewardDoesNotAsk() {
        // the starter's reward_words: a commercial that gives gems is fine to tap
        val ad = listOf(line("Get 20 Gems for watching!", 0.5, 0.3), line("Reward for Watching Video", 0.5, 0.45), line("Watch Video", 0.6, 0.7))
        assertEquals(ClaudeSafety.Verdict.Ok, ClaudeSafety.judge(ad, Point(0.6, 0.7)))
        // still refused when the same screen offers a purchase, and irreversible buttons still ask
        assertTrue(ClaudeSafety.judge(ad + line("Not enough Gems", 0.5, 0.9), Point(0.6, 0.7)) is ClaudeSafety.Verdict.Refuse)
        assertEquals(ClaudeSafety.Verdict.Confirm("Exchange"), ClaudeSafety.judge(ad + line("Exchange", 0.2, 0.85), Point(0.2, 0.85)))
    }

    @Test fun ordinaryScreensAreOk() {
        val lines = listOf(line("Quest", 0.3, 0.2), line("Menu", 0.9, 0.1), line("Village", 0.5, 0.05))
        assertEquals(ClaudeSafety.Verdict.Ok, ClaudeSafety.judge(lines, Point(0.3, 0.2)))
        // "Reselling" or "Released" in a story line far from the tap don't trigger
        assertEquals(ClaudeSafety.Verdict.Ok, ClaudeSafety.judge(listOf(line("The seal was released long ago", 0.5, 0.8)), Point(0.5, 0.2)))
    }
}
