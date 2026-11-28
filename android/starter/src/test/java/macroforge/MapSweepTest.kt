package macroforge

import macroforge.logic.MapSweep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map sweep (logic/MapSweep.kt) on a simulated map, bigger than a fixed sweep of 6 pans x 5 rows reaches. */
class MapSweepTest {
    private val safe = MapSweep.Safe(0.04, 0.9, 0.33, 0.9)

    /** A map of [mw] x [mh] screens; the view starts at ([ox], [oy]). Drags that start in the top bar or on a label do nothing. */
    private class FakeMap(val mw: Double, val mh: Double, var ox: Double, var oy: Double, val labels: List<Pair<String, Point>>) : MapSweep.View {
        val starts = ArrayList<Point>()
        var drags = 0
        private fun visible() = labels.filter { (_, p) -> p.x - ox in 0.05..0.95 && p.y - oy in 0.25..0.95 }
            .map { (n, p) -> Ocr.Line(n, Region(p.x - ox - 0.07, p.y - oy - 0.02, 0.14, 0.04)) }
        override fun read() = visible()
        override fun drag(from: Point, to: Point): Boolean {
            drags++; starts += from
            if (from.y < 0.33) return false                       // the top bar: no pan
            if (visible().any { it.box.contains(from) }) return false  // on a label: no pan
            val nx = (ox - (to.x - from.x)).coerceIn(0.0, mw - 1.0)
            val ny = (oy - (to.y - from.y)).coerceIn(0.0, mh - 1.0)
            val moved = nx != ox || ny != oy
            ox = nx; oy = ny
            return moved
        }
    }

    private fun grid(mw: Double, mh: Double): List<Pair<String, Point>> {
        val out = ArrayList<Pair<String, Point>>()
        var n = 0
        var y = 0.4
        while (y < mh) { var x = 0.2; while (x < mw) { out += "L${n++}" to Point(x, y); x += 0.45 }; y += 0.3 }
        // the far corners
        out += "Corner" to Point(mw - 0.15, mh - 0.1)
        out += "Origin" to Point(0.15, 0.3)
        return out
    }

    @Test fun readsEveryLabelOfAMapBiggerThanAFixedSweep() {
        val labels = grid(5.5, 4.5)
        val map = FakeMap(5.5, 4.5, 2.0, 1.5, labels)
        val seen = HashSet<String>()
        MapSweep.sweep(map, safe) { read -> read.forEach { seen += it.text }; false }
        assertEquals(labels.map { it.first }.toSet(), seen)
    }

    @Test fun dragsStayInsideTheSafeArea() {
        val map = FakeMap(4.0, 3.0, 1.0, 1.0, grid(4.0, 3.0))
        MapSweep.sweep(map, safe) { false }
        assertTrue(map.drags > 0)
        assertTrue(map.starts.all { safe.contains(it) })
    }

    @Test fun stopsWhenAVisitSucceeds() {
        val map = FakeMap(4.0, 3.0, 0.0, 0.0, grid(4.0, 3.0))
        assertTrue(MapSweep.sweep(map, safe) { read -> read.any { it.text == "Corner" } })
        assertTrue(map.ox > 2.0)
    }

    @Test fun aMapThatNeverMovesEndsInFewDrags() {
        val map = FakeMap(1.0, 1.0, 0.0, 0.0, grid(1.0, 1.0))
        MapSweep.sweep(map, safe) { false }
        // one pan to the left, one up, one east, one down: three starts each
        assertTrue("drags ${map.drags}", map.drags <= 4 * MapSweep.STARTS_PER_PAN)
    }

    @Test fun aStalledDragTriesDistinctStarts() {
        val moved = MapSweep.pan(object : MapSweep.View {
            val starts = ArrayList<Point>()
            override fun read() = emptyList<Ocr.Line>()
            override fun drag(from: Point, to: Point): Boolean { starts += from; return starts.size == 3 }
        }, 0.4, 0.0, emptyList(), safe)
        assertTrue("the third start moves it", moved)
    }

    @Test fun dragStartsLeaveARoomForTheWholeDrag() {
        val labels = listOf(Ocr.Line("A", Region(0.45, 0.5, 0.2, 0.05)))
        for (p in MapSweep.dragStarts(-0.4, 0.0, labels, safe).take(10)) {
            assertTrue(safe.contains(p) && safe.contains(Point(p.x - 0.4, p.y)))
        }
    }

    @Test fun shortLabelsMatchDespiteSwappedLetters() {
        assertTrue(Ocr.similarity("lzana", "Izana") < 0.85)
        assertEquals(1.0, MapSweep.looseSimilarity("lzana", "Izana"), 1e-9)
        assertEquals(1.0, MapSweep.looseSimilarity("N0rth", "North"), 1e-9)
        assertTrue(MapSweep.looseSimilarity("Izana", "Baruoki") < 0.5)
    }
}
