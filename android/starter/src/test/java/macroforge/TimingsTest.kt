package macroforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingsTest {
    @Test fun nestsPartsAndAddsThemUp() {
        val t = Timings()
        t.timed("on field") {
            t.timed("dungeon") {
                t.add("battle", 4000)
                t.add("battle", 6000)
                t.timed("map 1") { t.add("fish", 2000) }
            }
        }
        val s = t.snapshot()
        assertEquals(Timings.Entry(10_000, 2), s["on field › dungeon › battle"])
        assertEquals(Timings.Entry(2000, 1), s["on field › dungeon › map 1 › fish"])
        assertNotNull(s["on field › dungeon › map 1"])
        assertEquals(1, s.getValue("on field").n)
    }

    @Test fun hotspotsCountOwnTimeOnly() {
        val times = mapOf(
            "run" to Timings.Entry(100_000, 1),
            "run › map 1" to Timings.Entry(60_000, 1),
            "run › map 1 › fish" to Timings.Entry(45_000, 3),
            "run › boss" to Timings.Entry(30_000, 1),
        )
        assertEquals(15_000, Timings.own(times, "run › map 1"))
        val h = Timings.hotspots(times, top = 2)
        assertEquals(listOf("run › map 1 › fish", "run › boss"), h.map { it.first })
        assertEquals("run › map 1 › fish 45s (45%, 3×)", Timings.line(h[0].first, h[0].second, 100_000))
    }

    @Test fun capsAndSurvivesAStop() {
        val t = Timings()
        try { t.timed("a") { throw StopScenario("stop requested") } } catch (_: StopScenario) {}
        t.timed("b") {}
        assertTrue(t.snapshot().containsKey("b")) // not "a › b": the stop left the part
        repeat(Timings.MAX_PARTS + 20) { t.add("p$it", 1) }
        assertEquals(Timings.MAX_PARTS, t.snapshot().size)
    }

    @Test fun sinceGivesOneRound() {
        val t = Timings()
        t.add("walk", 1000)
        val before = t.snapshot()
        t.add("walk", 500); t.add("fish", 200)
        assertEquals(mapOf("walk" to Timings.Entry(500, 1), "fish" to Timings.Entry(200, 1)), t.since(before))
    }

    @Test fun savedWithTheRun() {
        val r = RunStats.Record("sea", 0L, 60_000L, "done", times = mapOf("on field › map 1" to Timings.Entry(42_000, 2)))
        val back = RunStats.fromJson(RunStats.toJson(r))!!
        assertEquals(r.times, back.times)
        val line = RunSummary.timeLine(back)!!
        assertTrue(line, line.startsWith("Time went to: on field › map 1 42s (70%, 2×)"))
    }
}
