package macroforge

import java.util.concurrent.ConcurrentHashMap

/**
 * Files the app could not load: a macro, a template library, a data file of the macro pack or the
 * logic pack. A broken file fails on its own: it is skipped and listed here, and everything else
 * (the other macros, the connector, the app's pages) keeps working.
 *
 * Shown by the connector (list_macros, macro_status), on the Macros page, in the log (once per
 * problem) and as a clear error when the user or Claude starts the macro of a broken file.
 */
object LoadProblems {
    /** [file]: where it came from (e.g. macros/daily/inbox.yaml); [kind]: macro, templates, data, logic, newer. */
    data class Problem(val file: String, val kind: String, val message: String, val time: Long)

    private val problems = ConcurrentHashMap<String, Problem>()

    /** Notified (on any thread) when a new problem shows up. */
    val listeners = java.util.concurrent.CopyOnWriteArrayList<(Problem) -> Unit>()

    /** Remember that [file] can't be loaded; logged once per file and message. True when it is new. */
    fun report(file: String, kind: String, message: String): Boolean {
        val msg = message.ifBlank { "unknown error" }.lines().first().take(300)
        val old = problems[file]
        if (old != null && old.message == msg) return false
        val p = Problem(file, kind, msg, System.currentTimeMillis())
        problems[file] = p
        if (kind == "newer") MacroLog.i("$file needs a newer app: $msg")
        else MacroLog.e("could not load $file (skipped, the rest keeps working): $msg")
        listeners.forEach { runCatching { it(p) } }
        return true
    }

    /** [file] loaded fine (again). */
    fun clear(file: String) { problems.remove(file) }

    /** Forget every problem of files under [prefix] (e.g. a new macro pack replaced them). */
    fun clearUnder(prefix: String) { problems.keys.removeIf { it.startsWith(prefix) } }

    /** The problems now, broken files first, by file. */
    fun all(): List<Problem> = problems.values.sortedWith(compareBy({ it.kind == "newer" }, { it.file }))

    /** The broken ones (not those that only need a newer app). */
    fun broken(): List<Problem> = all().filter { it.kind != "newer" }

    /** The problem of the macro file named [name] (its file name is the macro's name), if any. */
    fun forMacro(name: String): Problem? = problems.values.firstOrNull { p ->
        (p.kind == "macro" || p.kind == "newer") && p.file.substringAfterLast('/').removeSuffix(".yaml").removeSuffix(".yml") == name
    }

    /** Why the macro [name] isn't there, for an error message ("" when no file of that name failed). */
    fun whyMissing(name: String): String = forMacro(name)?.let {
        if (it.kind == "newer") " (${it.file} needs a newer app: ${it.message})"
        else " (its file ${it.file} can't be loaded: ${it.message}; fix the file)"
    }.orEmpty()

    /** One line per problem, for Claude and the app ("" when there is none). */
    fun describe(list: List<Problem> = all()): String = list.joinToString("\n") { p ->
        if (p.kind == "newer") "- ${p.file}: needs a newer app (${p.message})" else "- ${p.file} (${p.kind}): ${p.message}"
    }

    /** For tests. */
    fun reset() = problems.clear()
}
