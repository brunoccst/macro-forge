package macroforge


import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RunStatsTest {
    private fun rec(macro: String, start: Long, mins: Int, reason: String, counts: Map<String, Int> = emptyMap(), reports: List<Map<String, Any?>> = emptyList()) =
        RunStats.Record(macro, start, start + mins * 60_000L, reason, counts, reports)

    @Test fun classifiesStopReasons() {
        assertEquals(RunStats.Outcome.STOPPED, RunStats.classify("stop requested"))
        assertEquals(RunStats.Outcome.ERROR, RunStats.classify("error: IllegalStateException: boom"))
        assertEquals(RunStats.Outcome.SAFETY, RunStats.classify("safety stop: the shop or a purchase popup was on screen (no tap made there)"))
        assertEquals(RunStats.Outcome.STUCK, RunStats.classify("stuck on an unrecognised screen for 45s"))
        assertEquals(RunStats.Outcome.DONE, RunStats.classify("Forest Quest done — 13m 25s; chests 7/7"))
        assertEquals(RunStats.Outcome.DONE, RunStats.classify("finished"))
    }

    @Test fun groupsReasonsWithoutNumbers() {
        assertEquals("Forest Quest done", RunStats.reasonGroup("Forest Quest done — 13m 25s; chests 7/7, battles 12"))
        assertEquals("Forest Quest: n run(s) in nm", RunStats.reasonGroup("Forest Quest: 3 run(s) in 41m; out of keys"))
    }

    @Test fun storesClearsAndSummarizes() {
        val dir = Files.createTempDirectory("stats").toFile()
        RunStats.useDir(dir)
        RunStats.clear()
        val report = mapOf<String, Any?>("quest" to "Forest Quest", "seconds" to 805, "coins" to 7,
            "rare_drop" to true, "items" to mapOf("Feather" to 2), "rewards" to mapOf("Feather" to 3, "Coins" to 500))
        RunStats.record(rec("forest-quest", 1_000_000L, 13, "Forest Quest done — 13m", mapOf("battles" to 12, "coins" to 7), listOf(report)))
        RunStats.record(rec("forest-quest", 2_000_000L, 5, "stop requested", mapOf("battles" to 3)))
        RunStats.record(rec("inbox", 3_000_000L, 1, "finished"))
        assertEquals(3, RunStats.all().size)

        val s = RunStats.summarize(RunStats.all().filter { it.macro == "forest-quest" })
        assertEquals(2, s.runs)
        assertEquals(18 * 60_000L, s.totalMs)
        assertEquals(15, s.counts["battles"])
        assertEquals(1, s.outcomes[RunStats.Outcome.DONE])
        assertEquals(1, s.outcomes[RunStats.Outcome.STOPPED])
        assertEquals(1, s.reports)
        val totals = s.reportTotals.toMap()
        assertEquals("7", totals["Coins earned"])
        assertEquals("1", totals["Rare drops"])
        assertEquals(listOf("Rare drop" to 1), s.cardTotals)
        assertEquals("Coins" to 500, s.items.first())
        assertEquals(5, s.items.toMap()["Feather"])

        RunStats.clear("forest-quest")
        assertEquals(listOf("inbox"), RunStats.all().map { it.macro })
        RunStats.clear()
        assertTrue(RunStats.all().isEmpty())
    }

    @Test fun roundTripsJson() {
        val r = RunStats.Record("cave-quest", 5L, 65_005L, "done", mapOf("coins" to 7), listOf(mapOf("quest" to "Cave Quest", "rare_drop" to false)), "connector", "daily-routine")
        assertEquals(r, RunStats.fromJson(RunStats.toJson(r)))
    }

    @Test fun readsOnlyNewReports() {
        val dir = Files.createTempDirectory("files").toFile()
        val log = java.io.File(dir, "runs.log")
        log.writeText("{\"quest\":\"old\"}\n")
        val mark = RunStats.reportsMark(dir)
        log.appendText("{\"quest\":\"Forest Quest\",\"chests\":7}\n")
        val got = RunStats.reportsSince(dir, mark)
        assertEquals(1, got.size)
        assertEquals("Forest Quest", got[0]["quest"])
    }

    @Test fun countsRunsPerDay() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val day = 86_400_000L
        val now = 20 * day + 3_600_000L
        val runs = listOf(rec("a", 20 * day + 60_000, 10, "finished"), rec("a", 19 * day, 5, "finished"), rec("a", 5 * day, 5, "finished"))
        val per = RunStats.perDay(runs, 3, now, utc)
        assertEquals(listOf(0, 1, 1), per.map { it.second })
        assertEquals(10.0, per.last().third, 0.001)
    }

    @Test fun formatsDurations() {
        assertEquals("42s", RunStats.duration(42_000))
        assertEquals("13m 25s", RunStats.duration((13 * 60 + 25) * 1000L))
        assertEquals("1h 05m", RunStats.duration(65 * 60_000L))
    }

    @Test fun reportFieldsComeFromTheProfile() {
        val fields = listOf(
            GameProfile.ReportField("level", show = "level {value}"),
            GameProfile.ReportField("seconds", duration = true),
            GameProfile.ReportField("gems", show = "gems {value}", of = "gems_max", total = "Gems found"),
            GameProfile.ReportField("mode", total = "{Value} mode"),
            GameProfile.ReportField("rare", show = "rare drop", total = "Rare drops", card = "Rare drop"),
        )
        val reps = listOf(mapOf<String, Any?>("level" to "Cave", "seconds" to 65, "gems" to 2, "gems_max" to 3, "mode" to "hard", "rare" to true),
            mapOf<String, Any?>("level" to "Hill", "gems" to 1, "gems_max" to 3, "mode" to "hard", "rare" to false))
        assertEquals("• level Cave · 1m 05s · gems 2/3 · rare drop", RunSummary.reportLine(reps[0], fields))
        val (rows, card) = RunStats.reportTotals(reps, fields)
        assertEquals(listOf("Gems found" to "3 of 6 (50%)", "Hard mode" to "2", "Rare drops" to "1"), rows)
        assertEquals(listOf("Rare drop" to 1), card)
    }
}
