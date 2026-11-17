package macroforge

import macroforge.logic.AdSigns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Signs that a commercial is over (logic/AdSigns.kt), on made-up rows of grey values. */
class AdSignsTest {
    private val w = 300

    /** A bar row: [frac] of it [fill] from the left, the rest [track]. */
    private fun bar(frac: Double, fill: Int = 230, track: Int = 90) = IntArray(w) { if (it < frac * w) fill else track }

    /** Some picture: no flat colours. */
    private fun picture(seed: Int) = IntArray(w) { (it * 37 + seed * 11) % 256 }

    /** The bar at row 3 of a band of 10 rows of picture. */
    private fun band(barRow: IntArray?, seed: Int = 0) = (0 until 10).map { y -> y to if (y == 3 && barRow != null) barRow else picture(seed + y) }

    @Test fun readsATwoColourRow() {
        val r = AdSigns.readRow(bar(0.4))!!
        assertEquals(0.4, r.frac, 0.01)
        assertFalse(r.uniform)
        assertTrue(AdSigns.readRow(IntArray(w) { 120 })!!.uniform)
        assertNull(AdSigns.readRow(picture(1)))
        // two colours too close to tell apart: one colour, not a bar
        assertTrue(AdSigns.readRow(bar(0.4, fill = 100, track = 90))!!.uniform)
        // a bar with a margin of picture on both sides
        val framed = IntArray(w) { if (it < 30 || it >= 270) (it * 37) % 256 else if (it < 30 + 0.25 * 240) 230 else 90 }
        val r2 = AdSigns.readRow(framed)!!
        assertFalse(r2.uniform)
        assertEquals(0.25, r2.frac, 0.02)
    }

    @Test fun followsAFillingBarToItsEnd() {
        val b = AdSigns.Bar()
        // a 30 s commercial read every 2 s, its bar 1/30 further each second
        fun at(t: Double) = band(bar(0.10 + t / 30))
        assertEquals(-1.0, b.update(0.0, at(0.0)), 0.0)
        assertEquals(-1.0, b.update(2.0, at(2.0)), 0.0)
        val left = b.update(4.0, at(4.0))
        assertTrue("found the bar, seconds left $left", left > 20 && left < 26)
        assertTrue(b.found)
        var t = 4.0
        while (0.10 + (t + 2) / 30 < 0.98) { t += 2; assertTrue(b.update(t, at(t)) > 0) }
        // full: the row is all fill colour
        assertEquals(0.0, b.update(t + 2, band(IntArray(w) { 230 })), 0.0)
    }

    @Test fun aStillPictureIsNoBar() {
        val b = AdSigns.Bar()
        for (i in 0..6) assertEquals(-1.0, b.update(i * 2.0, band(bar(0.5))), 0.0)
        assertFalse(b.found)
    }

    @Test fun barGoneNearTheEndMeansOver() {
        val b = AdSigns.Bar()
        for ((i, f) in listOf(0.6, 0.7, 0.8, 0.9, 0.95).withIndex()) b.update(i * 2.0, band(bar(f)))
        assertTrue(b.found)
        // the end card: no bar any more
        assertEquals(0.0, b.update(12.0, band(null, seed = 5)), 0.0)
    }

    @Test fun theNextCommercialStartsAgain() {
        val b = AdSigns.Bar()
        for ((i, f) in listOf(0.7, 0.8, 0.9, 0.99).withIndex()) b.update(i * 2.0, band(bar(f)))
        assertEquals(0.0, b.update(8.0, band(bar(0.99))), 0.0)
        // a fresh bar: no longer over, and followed again
        assertEquals(-1.0, b.update(10.0, band(bar(0.05))), 0.0)
        b.update(12.0, band(bar(0.12)))
        assertTrue(b.update(14.0, band(bar(0.19))) > 10)
    }

    @Test fun anEmptyingBar() {
        val b = AdSigns.Bar()
        for ((i, f) in listOf(0.5, 0.4, 0.3, 0.2, 0.1).withIndex()) b.update(i * 2.0, band(bar(f)))
        assertTrue(b.found)
        assertEquals(0.0, b.update(10.0, band(bar(0.01))), 0.0)
    }

    @Test fun countdownTexts() {
        assertEquals(12, AdSigns.countdown(listOf("Reward in 12 seconds")))
        assertEquals(5, AdSigns.countdown(listOf("Ad", "5 seconds remaining")))
        assertEquals(7, AdSigns.countdown(listOf("7s left")))
        assertEquals(3, AdSigns.countdown(listOf("Close in 3 sec")))
        assertEquals(-1, AdSigns.countdown(listOf("Install", "Download now", "4.5 stars")))
    }

    @Test fun forfeitWarning() {
        assertTrue(AdSigns.forfeitWarning(listOf("Close video?", "You will lose your reward")))
        assertTrue(AdSigns.forfeitWarning(listOf("RESUME VIDEO")))
        assertFalse(AdSigns.forfeitWarning(listOf("Reward granted")))
    }
}
