package macroforge.logic

import macroforge.Ocr
import macroforge.Point

/**
 * A reusable sweep over a pannable map, so a macro can read every label of a map of any size.
 *
 * It goes to the top-left corner (pan until the view stops moving), then reads row by row: pan
 * along the row until the view stops, step down one row, pan back, and so on until stepping down
 * no longer moves the view (the bottom). Nothing in it depends on how large the map is.
 *
 * - Drags start and end only inside a [Safe] area, so they stay clear of bars, buttons and other
 *   controls where a drag doesn't pan.
 * - A drag that doesn't move the view is tried again from other starts ([STARTS_PER_PAN] distinct
 *   ones, off every label read last) before the view counts as being at an edge.
 *
 * Plain Kotlin over a [View], so it is tested on the JVM with a simulated map.
 */
object MapSweep {
    /** Where drags may start and end (fractions of the screen). */
    data class Safe(val xMin: Double = 0.04, val xMax: Double = 0.96, val yMin: Double = 0.22, val yMax: Double = 0.9) {
        fun contains(p: Point) = p.x in xMin..xMax && p.y in yMin..yMax
        val width get() = xMax - xMin
        val height get() = yMax - yMin
    }

    /** The screen side of a sweep. */
    interface View {
        /** The text now on screen. */
        fun read(): List<Ocr.Line>
        /** Drag from [from] to [to]; true when the view moved. */
        fun drag(from: Point, to: Point): Boolean
    }

    /** Different starts tried for one pan before the view counts as being at an edge. */
    const val STARTS_PER_PAN = 3
    private const val MAX_PANS_PER_ROW = 40
    private const val MAX_ROWS = 40
    /** Starts closer than this to one tried start are the same start. */
    private const val MIN_START_GAP = 0.08

    /**
     * Where a drag of ([dx], [dy]) can start, best first: off every label of [labels] and with the
     * whole drag inside [safe]. Labels sit thick on some maps, so starts across the width are tried.
     */
    fun dragStarts(dx: Double, dy: Double, labels: List<Ocr.Line>, safe: Safe = Safe()): List<Point> {
        fun free(p: Point) = labels.none { l ->
            val b = l.box
            p.x in (b.x - 0.03)..(b.x + b.w + 0.03) && p.y in (b.y - 0.05)..(b.y + b.h + 0.05)
        }
        val mid = Point((safe.xMin + safe.xMax) / 2, (safe.yMin + safe.yMax) / 2)
        val all = ArrayList<Point>()
        val ys = listOf(0.0, -0.13, 0.13, -0.2, 0.2, -0.26, 0.26)
        for (ox in listOf(0.0, -0.12, 0.12, -0.24, 0.24, -0.36, 0.36)) for (oy in ys) {
            val p = Point(mid.x - dx / 2 + ox, mid.y - dy / 2 + oy)
            if (safe.contains(p) && safe.contains(Point(p.x + dx, p.y + dy))) all += p
        }
        if (all.isEmpty()) all += Point((mid.x - dx / 2).coerceIn(safe.xMin, safe.xMax), (mid.y - dy / 2).coerceIn(safe.yMin, safe.yMax))
        return all.filter { free(it) } + all.filterNot { free(it) }
    }

    /**
     * Pan the view by ([dx], [dy]): up to [STARTS_PER_PAN] distinct starts (see [dragStarts]);
     * true when one moved the view, false when none did (an edge).
     */
    fun pan(view: View, dx: Double, dy: Double, labels: List<Ocr.Line>, safe: Safe = Safe(), log: (String) -> Unit = {}): Boolean {
        val tried = ArrayList<Point>()
        for (from in dragStarts(dx, dy, labels, safe)) {
            if (tried.any { Math.hypot(it.x - from.x, it.y - from.y) < MIN_START_GAP }) continue
            tried += from
            if (view.drag(from, Point(from.x + dx, from.y + dy))) return true
            log("map drag (${fmt(dx)}, ${fmt(dy)}) from (${fmt(from.x)}, ${fmt(from.y)}) didn't move the view")
            if (tried.size >= STARTS_PER_PAN) break
        }
        return false
    }

    /**
     * [Ocr.norm] with the letters a small font swaps folded together (l, 1 -> i; 0 -> o), so a
     * short label read "lzana" still matches "Izana". (Kept here, not in the shell, so it ships
     * with the logic pack.)
     */
    fun fold(s: String) = Ocr.norm(s).replace('l', 'i').replace('1', 'i').replace('0', 'o')

    /** [Ocr.similarity] of the [fold]ed texts: for short labels, where one swapped letter costs a lot. */
    fun looseSimilarity(a: String, b: String): Double = Ocr.similarity(fold(a), fold(b))

    private fun fmt(v: Double) = "%.2f".format(v)

    /**
     * Sweep the map shown: the view as it is, then every view from the top-left corner on, row by
     * row, calling [visit] with each view's text until it returns true (then true).
     * [stepX] and [stepY] are the pans along and down a row (fractions of the screen).
     */
    fun sweep(view: View, safe: Safe = Safe(), stepX: Double = 0.4, stepY: Double = 0.35,
              log: (String) -> Unit = {}, visit: (List<Ocr.Line>) -> Boolean): Boolean {
        var seen = view.read()
        if (visit(seen)) return true
        // the top-left corner: pan until the view stops moving, left then up
        val cornerX = minOf(0.6, safe.width * 0.8)
        val cornerY = minOf(0.4, safe.height * 0.8)
        var moved = false
        for (i in 0 until MAX_PANS_PER_ROW) {
            if (!pan(view, cornerX, 0.0, seen, safe, log)) break
            moved = true; seen = view.read()
        }
        for (i in 0 until MAX_ROWS) {
            if (!pan(view, 0.0, cornerY, seen, safe, log)) break
            moved = true; seen = view.read()
        }
        log("map sweep: top-left corner reached")
        if (moved && visit(seen)) return true
        var dir = -1.0   // the finger goes left: the view goes east
        for (row in 0 until MAX_ROWS) {
            for (i in 0 until MAX_PANS_PER_ROW) {
                if (!pan(view, dir * stepX, 0.0, seen, safe, log)) break
                seen = view.read()
                if (visit(seen)) return true
            }
            if (!pan(view, 0.0, -stepY, seen, safe, log)) break   // the bottom
            seen = view.read()
            if (visit(seen)) return true
            dir = -dir
        }
        log("map sweep: bottom reached")
        return false
    }
}
