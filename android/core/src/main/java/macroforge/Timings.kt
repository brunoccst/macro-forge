package macroforge

/**
 * Where a macro run's time goes: how long each part of it took, added up over the run, kept in
 * memory only (no log lines) and saved with the run's statistics ([RunStats.Record.times]).
 *
 * A part is timed with [timed]; parts timed inside it are its children, so every entry is a path
 * ("another_dungeon › map 2 › fish"). An entry's time includes its children's. The runner times
 * the screen handlers and the macros run inside each other by itself, the YAML steps time the
 * game's own steps and waits, and logic code times its own parts (a dungeon's maps, legs, battles).
 */
class Timings {
    /** One part: its time in total (ms) and how many times it ran. */
    data class Entry(val ms: Long, val n: Int)

    private val entries = LinkedHashMap<String, LongArray>()
    private val stack = ArrayList<String>()

    /** Run [block] as the part [label], inside whatever part is being timed now. */
    fun <T> timed(label: String, block: () -> T): T {
        val name = clean(label)
        if (name.isEmpty()) return block()
        val path = synchronized(this) {
            val p = (stack + name).joinToString(SEP)
            // a cap on how many parts and how deep, so a run that times in a loop can't grow without end
            if (stack.size >= MAX_DEPTH || (p !in entries && entries.size >= MAX_PARTS)) null else { stack += name; p }
        } ?: return block()
        val t0 = System.currentTimeMillis()
        try {
            return block()
        } finally {
            synchronized(this) {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                addAt(path, System.currentTimeMillis() - t0, 1)
            }
        }
    }

    /** Add [ms] to the part [label] under the part being timed now (a time measured some other way). */
    fun add(label: String, ms: Long, n: Int = 1) = synchronized(this) {
        val name = clean(label)
        if (name.isEmpty()) return@synchronized
        addAt((stack + name).joinToString(SEP), ms, n)
    }

    private fun addAt(path: String, ms: Long, n: Int) {
        val e = entries[path] ?: if (entries.size >= MAX_PARTS) return else LongArray(2).also { entries[path] = it }
        e[0] += ms.coerceAtLeast(0); e[1] += n.toLong()
    }

    /** Every part so far, by path, in the order first seen. */
    fun snapshot(): Map<String, Entry> = synchronized(this) { entries.mapValuesTo(LinkedHashMap()) { Entry(it.value[0], it.value[1].toInt()) } }

    /** The parts timed since [before] (a [snapshot]): e.g. one round of a loop. */
    fun since(before: Map<String, Entry>): Map<String, Entry> = snapshot().mapNotNull { (k, e) ->
        val b = before[k]
        val ms = e.ms - (b?.ms ?: 0); val n = e.n - (b?.n ?: 0)
        if (n > 0 || ms > 0) k to Entry(ms, n) else null
    }.toMap(LinkedHashMap())

    companion object {
        /** Between a part and the parts inside it. */
        const val SEP = " › "
        const val MAX_PARTS = 150
        const val MAX_DEPTH = 7

        private fun clean(label: String) = label.replace(SEP, " ").replace('\n', ' ').trim().take(60)

        /** The parent of [path] ("" at the top). */
        fun parent(path: String) = path.substringBeforeLast(SEP, "")

        /** The time of [path] not spent in the parts inside it. */
        fun own(times: Map<String, Entry>, path: String): Long =
            (times[path]?.ms ?: 0) - times.filterKeys { parent(it) == path }.values.sumOf { it.ms }

        /**
         * The parts that took the most time of their own (not counting the parts inside them), the
         * longest first: where the time really goes. [minMs] leaves out the small ones.
         */
        fun hotspots(times: Map<String, Entry>, top: Int = 6, minMs: Long = 1000): List<Pair<String, Entry>> =
            times.keys.map { it to Entry(own(times, it), times.getValue(it).n) }
                .filter { it.second.ms >= minMs }.sortedByDescending { it.second.ms }.take(top)

        /** The same part added up over several runs. */
        fun sum(all: List<Map<String, Entry>>): Map<String, Entry> {
            val out = LinkedHashMap<String, Entry>()
            for (m in all) for ((k, e) in m) out[k] = out[k]?.let { Entry(it.ms + e.ms, it.n + e.n) } ?: e
            return out
        }

        /** "map 2 › fish 3m 10s (21%, 12×)": one hotspot for a text line, as a share of [totalMs]. */
        fun line(path: String, e: Entry, totalMs: Long): String =
            "$path ${RunStats.duration(e.ms)}" + buildList {
                if (totalMs > 0) add("${(e.ms * 100 / totalMs)}%")
                if (e.n > 1) add("${e.n}×")
            }.let { if (it.isEmpty()) "" else " (${it.joinToString(", ")})" }

        /** As saved in runs.jsonl / a run report: {"path": [ms, n]}. */
        fun toJson(times: Map<String, Entry>): Map<String, List<Long>> = times.mapValuesTo(LinkedHashMap()) { listOf(it.value.ms, it.value.n.toLong()) }

        fun fromJson(v: Any?): Map<String, Entry> = (v as? Map<*, *>).orEmpty().mapNotNull { (k, x) ->
            val l = x as? List<*> ?: return@mapNotNull null
            val ms = (l.getOrNull(0) as? Number)?.toLong() ?: return@mapNotNull null
            k.toString() to Entry(ms, (l.getOrNull(1) as? Number)?.toInt() ?: 1)
        }.toMap(LinkedHashMap())
    }
}
