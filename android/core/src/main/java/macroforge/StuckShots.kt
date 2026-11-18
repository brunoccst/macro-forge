package macroforge

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Screenshots of failed runs: when a macro gets stuck, fails with an error or makes a safety stop,
 * the runner saves the screen as it was at that moment (a JPEG the size of the connector's
 * screenshots) next to a small JSON note (macro, reason, step, last screen). The outcome message
 * names the file, and Claude sees them with the connector's `stuck_screenshot` tool, so an unknown
 * popup that stopped a scheduled run can be looked at afterwards.
 *
 * Kept in the app's own storage only (never uploaded), newest [KEEP] kept. Plain JVM code apart
 * from the image encoding (done by the caller), so it is unit tested without a device.
 */
object StuckShots {
    const val KEEP = 20

    data class Shot(
        /** The JPEG's file name, e.g. "20261118-150412-start-game.jpg". */
        val name: String,
        val time: Long,
        val macro: String,
        val reason: String,
        val step: String? = null,
        val screen: String? = null,
    )

    private val json = ObjectMapper()

    fun dir(filesDir: File) = File(filesDir, "stuck")

    /** Whether a run that stopped for [reason] gets a screenshot: it got stuck, failed or made a safety stop. */
    fun wanted(reason: String) = RunStats.classify(reason) in setOf(RunStats.Outcome.STUCK, RunStats.Outcome.ERROR, RunStats.Outcome.SAFETY)

    /** The file name for a shot of [macro] at [time] (local time, sortable). */
    fun fileName(macro: String, time: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = zone }.format(Date(time))
        val safe = macro.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(40).ifEmpty { "macro" }
        return "$stamp-$safe.jpg"
    }

    /** Save [jpeg] for a run of [macro] that stopped for [reason]; keeps the newest [keep]. The saved shot. */
    fun save(dir: File, jpeg: ByteArray, macro: String, reason: String, step: String?, screen: String?,
             time: Long = System.currentTimeMillis(), keep: Int = KEEP): Shot {
        dir.mkdirs()
        var name = fileName(macro, time)
        var n = 2
        while (File(dir, name).exists()) name = fileName(macro, time).removeSuffix(".jpg") + "-${n++}.jpg"
        File(dir, name).writeBytes(jpeg)
        val shot = Shot(name, time, macro, reason, step?.takeIf { it.isNotBlank() }, screen?.takeIf { it.isNotBlank() })
        val m = LinkedHashMap<String, Any?>()
        m["time"] = time; m["macro"] = macro; m["reason"] = reason
        shot.step?.let { m["step"] = it }
        shot.screen?.let { m["screen"] = it }
        File(dir, note(name)).writeText(json.writeValueAsString(m))
        prune(dir, keep)
        return shot
    }

    /** The saved shots, newest first. */
    @Suppress("UNCHECKED_CAST")
    fun list(dir: File): List<Shot> {
        val jpgs = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return emptyList()
        return jpgs.map { f ->
            val m = runCatching { json.readValue(File(dir, note(f.name)).readText(), Map::class.java) as Map<String, Any?> }.getOrDefault(emptyMap())
            Shot(f.name, (m["time"] as? Number)?.toLong() ?: f.lastModified(), m["macro"]?.toString() ?: f.name.removeSuffix(".jpg"),
                m["reason"]?.toString().orEmpty(), m["step"]?.toString(), m["screen"]?.toString())
        }.sortedWith(compareByDescending<Shot> { it.time }.thenByDescending { it.name })
    }

    /** One shot's JPEG by file name (only files of [dir]), or null. */
    fun read(dir: File, name: String): ByteArray? {
        val clean = File(name.trim()).name
        if (clean.isEmpty() || !clean.endsWith(".jpg")) return null
        return File(dir, clean).takeIf { it.isFile }?.readBytes()
    }

    /** Keep the newest [keep] shots (and their notes). */
    fun prune(dir: File, keep: Int = KEEP) {
        for (s in list(dir).drop(keep)) {
            File(dir, s.name).delete()
            File(dir, note(s.name)).delete()
        }
    }

    /** One line on a shot, for lists. */
    fun describe(s: Shot, zone: TimeZone = TimeZone.getDefault()): String {
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }.format(Date(s.time))
        val where = listOfNotNull(s.step?.let { "doing \"$it\"" }, s.screen?.let { "last screen seen: $it" }).joinToString("; ")
        return "${s.name} — $at, ${s.macro}: ${s.reason}" + if (where.isEmpty()) "" else " ($where)"
    }

    private fun note(jpg: String) = jpg.removeSuffix(".jpg") + ".json"
}
