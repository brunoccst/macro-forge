package macroforge.logic

/**
 * Signs, read from the screen, that a commercial has played to its end, so a macro can close it
 * as soon as its reward counts instead of waiting a fixed time. Nothing here taps anything: the
 * macro decides (it only ever presses Back on a commercial).
 *
 * - [Bar]: many commercials show a thin progress bar along the top (or bottom) of the video that
 *   fills up (or empties) as it plays. A row of the screen that is two flat colours, one from the
 *   left and one from the right, is a bar candidate; it becomes "the bar" only once the split
 *   moves the same way over a few reads (a still picture or the video's own content doesn't), and
 *   from its speed the seconds left are known. Full (or empty) = the commercial is over.
 * - [countdown]: commercials that write "Reward in 12 seconds", "12 seconds remaining", ...
 * - [forfeitWarning]: the "Close video? You will lose your reward" box some commercials show when
 *   closed too early (Back on it = keep watching).
 *
 * Plain Kotlin on rows of grey values (0..255), so it is tested on the JVM with no device; the
 * screen side (OpenCV) is in FlowRun.
 */
object AdSigns {
    /** Grey levels two pixels may differ by and still be "the same flat colour". */
    const val TOL = 20
    /** Fill and track colours of a bar differ by at least this much. */
    const val CONTRAST = 25

    /**
     * One row read as a bar between [start] and [end] (pixels; a bar may not span the whole row):
     * [frac] of it is the colour [left], the rest the colour [right]. [uniform]: one colour only
     * ([left] == [right]), e.g. a full bar.
     */
    data class Reading(val frac: Double, val left: Int, val right: Int, val uniform: Boolean, val start: Int = 0, val end: Int = 0)

    private fun mid(a: Int, b: Int, c: Int) = maxOf(minOf(a, b), minOf(maxOf(a, b), c))

    private class Run(val start: Int, var len: Int, val colour: Int) { val end get() = start + len }

    /** The row as a two-colour bar (at least half the row wide), a single colour, or null (a picture, text, ...). */
    fun readRow(row: IntArray): Reading? {
        val n = row.size
        if (n < 40) return null
        // a median of 3 takes out single stray pixels, then flat runs of one colour each
        val sm = IntArray(n) { i -> mid(row[maxOf(i - 1, 0)], row[i], row[minOf(i + 1, n - 1)]) }
        val runs = ArrayList<Run>()
        for (i in 0 until n) {
            val last = runs.lastOrNull()
            if (last != null && kotlin.math.abs(sm[i] - last.colour) <= TOL) last.len++ else runs.add(Run(i, 1, sm[i]))
        }
        // two long runs of clearly different colours side by side (a soft edge of up to 3 pixels between)
        var best: Reading? = null
        for ((ia, ra) in runs.withIndex()) {
            if (ra.len < 2) continue
            var j = ia + 1
            var junk = 0
            while (j < runs.size && runs[j].len <= 3 && junk + runs[j].len <= 3) { junk += runs[j].len; j++ }
            if (j < runs.size && j > ia) {
                val rb = runs[j]
                // not a stray pixel run: the other one has to be a real run too
                val total = ra.len + junk + rb.len
                if (rb.len >= 2 && kotlin.math.abs(ra.colour - rb.colour) >= CONTRAST && total >= n / 2 && total > (best?.let { it.end - it.start } ?: 0))
                    best = Reading((ra.len + junk / 2.0) / total, ra.colour, rb.colour, uniform = false, start = ra.start, end = rb.end)
            }
        }
        best?.let { return it }
        val one = runs.maxByOrNull { it.len } ?: return null
        return if (one.len >= n / 2) Reading(1.0, one.colour, one.colour, uniform = true, start = one.start, end = one.end) else null
    }

    /**
     * Follows the progress bar of the commercial on screen across reads ([update] with the band of
     * rows to look in, top and bottom of the picture, each with its height). One per macro run.
     */
    class Bar {
        private class Track(val y: Int, val left: Int, val right: Int, val start: Int, val end: Int) {
            val samples = ArrayList<Pair<Double, Double>>() // time s, frac
            var misses = 0
        }

        private var tracks = HashMap<Int, Track>()
        private var bar: Track? = null
        private var lastFrac = -1.0
        private var gone = false
        private var goneAt = 0.0

        private fun over(t: Double) { gone = true; goneAt = t }

        /** The bar was found: rising (or falling) steadily over a few reads. */
        val found: Boolean get() = bar != null

        /** How far the bar is, 0..1 (-1: no bar). */
        val progress: Double get() = bar?.let { if (rising(it)) lastFrac else 1 - lastFrac } ?: -1.0

        fun reset() { tracks = HashMap(); bar = null; lastFrac = -1.0; gone = false }

        /**
         * Reads the rows (y to row) at time [t] (seconds); returns the seconds left until the bar is
         * full: 0 = full, the commercial is over; -1 = no bar known.
         */
        fun update(t: Double, rows: List<Pair<Int, IntArray>>): Double {
            val readings = rows.mapNotNull { (y, r) -> readRow(r)?.let { y to it } }
            bar?.let { b -> follow(b, t, readings)?.let { return it } }
            return seek(t, readings)
        }

        private fun seek(t: Double, readings: List<Pair<Int, Reading>>): Double {
            // candidates: two-colour rows; one track per row and pair of colours
            val seen = HashSet<Int>()
            for ((y, r) in readings) {
                if (r.uniform) continue
                val tr = tracks[y]?.takeIf { same(it.left, r.left) && same(it.right, r.right) && near(it, r) } ?: Track(y, r.left, r.right, r.start, r.end).also { tracks[y] = it }
                tr.samples.add(t to r.frac)
                if (tr.samples.size > 12) tr.samples.removeAt(0)
                tr.misses = 0
                seen.add(y)
            }
            for ((y, tr) in tracks) if (y !in seen) tr.misses++
            tracks.values.removeAll { it.misses > 1 }
            // the bar: the top-most track that moved steadily the same way
            val b = tracks.values.filter { steady(it) }.minByOrNull { it.y } ?: return -1.0
            bar = b
            lastFrac = b.samples.last().second
            return left(b)
        }

        /** The known bar's seconds left; null: it started over (the next commercial), look again. */
        private fun follow(b: Track, t: Double, readings: List<Pair<Int, Reading>>): Double? {
            val r = readings.firstOrNull { (y, r) -> kotlin.math.abs(y - b.y) <= 2 && near(b, r) && (r.uniform || same(r.left, b.left) && same(r.right, b.right)) }?.second
            val up = rising(b)
            if (gone) {
                // over; a bar starting again (the next commercial of a group), or a while later: look anew
                val fresh = r != null && !r.uniform && (if (up) r.frac < 0.5 else r.frac > 0.5)
                if (fresh || t - goneAt > 10) { reset(); return null }
                return 0.0
            }
            when {
                // the row is all one colour: full (fill colour) or empty (track colour)
                r != null && r.uniform -> {
                    val fill = if (up) b.left else b.right
                    if (same(r.left, fill) && progress >= 0.75) { over(t); return 0.0 }
                    return left(b)
                }
                r != null -> {
                    // a fresh bar (the next commercial of a group): start again
                    if (if (up) r.frac < lastFrac - 0.1 else r.frac > lastFrac + 0.1) { reset(); return null }
                    b.samples.add(t to r.frac)
                    b.misses = 0
                    lastFrac = r.frac
                    if (if (up) r.frac >= 0.985 else r.frac <= 0.015) { over(t); return 0.0 }
                    return left(b)
                }
                else -> {
                    // the bar went away: nearly done = the end card is up; else lost it
                    b.misses++
                    if (progress >= 0.9) { over(t); return 0.0 }
                    if (b.misses > 3) reset()
                    return -1.0
                }
            }
        }

        private fun same(a: Int, b: Int) = kotlin.math.abs(a - b) <= TOL

        /** The reading spans the same stretch of the row as the track (a few pixels either way). */
        private fun near(tr: Track, r: Reading): Boolean {
            val slack = maxOf(6, (tr.end - tr.start) / 25)
            return kotlin.math.abs(r.start - tr.start) <= slack && kotlin.math.abs(r.end - tr.end) <= slack
        }

        private fun rising(b: Track) = b.samples.last().second >= b.samples.first().second

        /** At least 3 reads over 1.5 s or more, each further the same way, by 2 % or more in all. */
        private fun steady(tr: Track): Boolean {
            val s = tr.samples
            if (s.size < 3 || s.last().first - s.first().first < 1.5) return false
            val d = s.last().second - s.first().second
            if (kotlin.math.abs(d) < 0.02) return false
            return s.zipWithNext().all { (a, b) -> if (d > 0) b.second >= a.second - 0.003 else b.second <= a.second + 0.003 }
        }

        /** Seconds until full (or empty), from its speed since the first read. */
        private fun left(b: Track): Double {
            val (t0, f0) = b.samples.first()
            val (t1, f1) = b.samples.last()
            val speed = (f1 - f0) / (t1 - t0).coerceAtLeast(0.1)
            if (kotlin.math.abs(speed) < 1e-4) return -1.0
            val rest = if (speed > 0) (1 - f1) / speed else f1 / -speed
            return rest.coerceAtLeast(0.0).let { if (it < 0.05) 0.05 else it }
        }
    }

    // --- text ---------------------------------------------------------------------

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private val COUNTDOWNS = listOf(
        Regex("""reward in (\d{1,3})"""),                       // "Reward in 12 seconds", "reward in 5s"
        Regex("""(\d{1,3}) ?s(ec|econds?)? (remaining|left)"""), // "12 seconds remaining", "5s left"
        Regex("""(?:remaining|left) (\d{1,3}) ?s(ec|econds?)?\b"""),
        Regex("""in (\d{1,3}) ?sec(ond)?s?\b"""),                // "Close in 5 seconds", "skip in 5 sec"
    )

    /** Seconds a commercial's own countdown text says are left ("Reward in 12 seconds"), or -1. */
    fun countdown(lines: List<String>): Int {
        for (l in lines) {
            val t = norm(l).replace(Regex("""(\d) s\b"""), "$1s")
            for (re in COUNTDOWNS) re.find(t)?.let { m -> return m.groupValues[1].toInt() }
        }
        return -1
    }

    /** "Close video? You will lose your reward": the commercial was about to be closed too early. */
    fun forfeitWarning(lines: List<String>): Boolean = lines.map(::norm).any { l ->
        "lose your reward" in l || "lose the reward" in l || "lose reward" in l || "not receive" in l && "reward" in l ||
            "resume video" in l || "close video" in l
    }
}
