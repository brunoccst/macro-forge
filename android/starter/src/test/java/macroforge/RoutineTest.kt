package macroforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineTest {
    private val required = setOf("start-game")
    private val always = setOf("stop-game")

    @Test fun aRequiredStepThatFailsSkipsTheRestButTheAlwaysOnes() {
        val skipping = Routine.skipAfter("start-game", "stuck on an unrecognised screen for 180s", required)
        assertEquals("start-game failed", skipping)
        assertFalse(Routine.runs("daily-ads", skipping, always))
        assertTrue(Routine.runs("stop-game", skipping, always))
    }

    @Test fun onlyStuckErrorAndFailedCountAsFailed() {
        assertNull(Routine.skipAfter("start-game", "the game is on and on the field (login-bonus ad watched)", required))
        assertNull(Routine.skipAfter("start-game", "stop requested", required))
        assertEquals("start-game failed", Routine.skipAfter("start-game", "failed: a commercial did not close", required))
        assertEquals("start-game failed", Routine.skipAfter("start-game", "error: IllegalStateException: boom", required))
        assertEquals("start-game is not installed", Routine.skipAfter("start-game", null, required))
    }

    @Test fun otherStepsFailingDontStopTheRoutine() {
        assertNull(Routine.skipAfter("daily-ads", "stuck on an unrecognised screen for 45s", required))
        assertTrue(Routine.runs("inbox", null, always))
    }

    @Test fun theOutcomeSaysWhatWasSkipped() {
        val steps = listOf(
            RunStats.Record("start-game", 0L, 180_000L, "stuck on an unrecognised screen for 180s", routine = "daily-routine"),
            RunStats.Record("stop-game", 180_000L, 190_000L, "the game is off", routine = "daily-routine"),
        )
        val o = RunSummary.ofRoutine("daily-routine", steps, emptyList(), "finished", 190_000L,
            skipped = listOf("daily-ads", "inbox"), skipping = "start-game failed")
        assertFalse(o.ok)
        assertTrue(o.title, o.title.startsWith("Daily routine stopped early"))
        assertTrue(o.text, o.lines.contains("Skipped (start-game failed): Daily ads, Inbox"))
    }
}
