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

    @Test fun aStepPickedInAnOptionRunsThePickedMacro() {
        val step = RoutineStep("{boss}", mapOf("tries" to "{boss_tries}", "fast" to "true"))
        assertEquals("fight-boss" to mapOf("tries" to "3", "fast" to "true"), Routine.fill(step, mapOf("boss" to "fight-boss", "boss_tries" to "3")))
        assertNull(Routine.fill(step, mapOf("boss" to "none")))
        assertNull(Routine.fill(step, mapOf("boss" to " ")))
        assertEquals("inbox" to emptyMap<String, String>(), Routine.fill(RoutineStep("inbox", emptyMap()), emptyMap()))
        // a routine's steps: names, or {macro, params}
        val r = FlowScenario(mapOf("name" to "r", "steps" to listOf("a", mapOf("macro" to "{boss}", "params" to mapOf("fast" to true)))), "r")
        assertEquals(listOf("a", "{boss}"), r.steps)
        assertEquals(mapOf("fast" to "true"), r.routineSteps[1].params)
    }

    @Test fun everyStepHasAnIncludeSwitchThatDefaultsToOn() {
        val r = FlowScenario(mapOf("name" to "r", "params" to mapOf("include_b" to mapOf("default" to false, "help" to "own")),
            "steps" to listOf("a", "b", "{boss}", "a", mapOf("macro" to "{boss}"))), "r")
        // one switch per step (a repeated step shares it), the routine's own declaration wins
        assertEquals(setOf("include_b", "include_a", "include_boss"), r.params.keys)
        assertEquals("true", r.params.getValue("include_a").default)
        assertTrue(r.params.getValue("include_a").isBool)
        assertEquals("false", r.params.getValue("include_b").default)
        val a = RoutineStep("a", emptyMap())
        assertFalse(Routine.isOff(a, mapOf("include_a" to "true")))
        assertFalse(Routine.isOff(a, emptyMap()))
        assertTrue(Routine.isOff(a, mapOf("include_a" to "false")))
        assertTrue(Routine.isOff(RoutineStep("{boss}", emptyMap()), mapOf("include_boss" to "false")))
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
