package macroforge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.TimeZone

class StuckShotsTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun onlyFailuresGetAShot() {
        assertTrue(StuckShots.wanted("stuck on an unrecognised screen for 45s"))
        assertTrue(StuckShots.wanted("error: IllegalStateException: boom"))
        assertTrue(StuckShots.wanted("safety stop: the shop was on screen"))
        assertTrue(StuckShots.wanted("the screen stayed black for 120 s: the screen is off"))
        assertFalse(StuckShots.wanted("finished"))
        assertFalse(StuckShots.wanted("stop requested"))
        assertFalse(StuckShots.wanted("Forest Quest done — 13m 25s"))
    }

    @Test fun namesAreSortableAndSafe() {
        assertEquals("20261118-150412-start-game.jpg", StuckShots.fileName("start-game", 1_795_014_252_000L, utc))
        assertEquals("20261118-150412-my-macro.jpg", StuckShots.fileName("My Macro!", 1_795_014_252_000L, utc))
    }

    @Test fun savesListsReadsAndKeepsTheNewest() {
        val dir = Files.createTempDirectory("stuck").toFile()
        val t0 = 1_795_014_252_000L
        for (i in 0 until 5) StuckShots.save(dir, byteArrayOf(i.toByte()), "start-game", "stuck on an unrecognised screen for 45s",
            "start-game: waiting", "start-game: login_bonus", time = t0 + i * 60_000L, keep = 3)
        val shots = StuckShots.list(dir)
        assertEquals(3, shots.size)
        assertEquals(t0 + 4 * 60_000L, shots.first().time) // newest first
        assertEquals("start-game: login_bonus", shots.first().screen)
        assertArrayEquals(byteArrayOf(4), StuckShots.read(dir, shots.first().name))
        assertEquals(6, dir.listFiles()!!.size) // 3 images + 3 notes
        // two in the same second don't overwrite each other
        StuckShots.save(dir, byteArrayOf(9), "inbox", "error: x", null, null, time = t0 + 10 * 60_000L)
        StuckShots.save(dir, byteArrayOf(8), "inbox", "error: y", null, null, time = t0 + 10 * 60_000L)
        assertEquals(2, StuckShots.list(dir).count { it.macro == "inbox" })
        // only files of the folder can be read
        assertNull(StuckShots.read(dir, "../../etc/passwd"))
        assertNull(StuckShots.read(dir, "nope.jpg"))
    }

    @Test fun recordKeepsTheShotName() {
        val r = RunStats.Record("start-game", 1L, 2L, "stuck on an unrecognised screen for 45s", shot = "20261118-150412-start-game.jpg")
        assertEquals("20261118-150412-start-game.jpg", RunStats.fromJson(RunStats.toJson(r))!!.shot)
        val o = RunSummary.of(r)
        assertTrue(o.lines.any { it.startsWith("Screenshot: 20261118-150412-start-game.jpg") })
    }
}
