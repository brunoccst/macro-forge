package macroforge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * A daily run with nobody watching: one macro (by default the daily routine) starts every day a
 * few minutes after the game's daily reset (game.yaml `daily_reset:`, or the user's own time), while screen capture is on. Android's alarm wakes
 * the app; the run then goes like one started with ▶, with the macro's own saved options, and
 * ends with the usual outcome message and notification.
 *
 * If screen capture is off at that time, nothing runs and a notification says so. If another
 * macro is running, it tries again every [RETRY_MS], up to [RETRIES] times.
 *
 * The alarm is exact where Android allows it (USE_EXACT_ALARM), else inexact. Android drops alarms
 * on a reboot and an app update, so [ScheduleReceiver] sets it again then. Each alarm set is
 * remembered: an alarm that never went off (battery saving, e.g. Samsung's sleeping apps) is
 * reported once as a problem in the news, and macro_status shows the next run ([status]).
 */
object DailySchedule {
    private const val PREFS = "schedule"
    const val DEFAULT_MACRO = "daily-routine"
    const val DEFAULT_DELAY_MIN = 10
    const val RETRY_MS = 15 * 60_000L
    const val RETRIES = 4
    private const val EXTRA_TRY = "try"
    /** An alarm this late with no run is reported as missed. */
    const val MISSED_AFTER_MS = 60 * 60_000L

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun macro(c: Context) = prefs(c).getString("macro", null)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MACRO
    fun delayMin(c: Context) = prefs(c).getInt("delay_min", DEFAULT_DELAY_MIN)
    /** The game's daily reset in minutes after midnight UTC: the user's own time, else game.yaml's, else midnight. */
    fun resetMin(c: Context) = prefs(c).getInt("reset_min", -1).takeIf { it in 0 until 24 * 60 } ?: gameResetMin()
    /** The reset game.yaml gives (midnight UTC without one). */
    fun gameResetMin() = GameProfile.current.dailyResetUtcMin ?: 0
    /** Whether the user set their own reset time (else game.yaml's counts). */
    fun resetIsCustom(c: Context) = prefs(c).getInt("reset_min", -1) in 0 until 24 * 60

    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("on", on).apply(); arm(c) }
    fun setMacro(c: Context, name: String) { prefs(c).edit().putString("macro", name.trim()).apply() }
    /** The user's own reset time (minutes after midnight UTC), or null to follow game.yaml again. */
    fun setResetMin(c: Context, min: Int?) { prefs(c).edit().putInt("reset_min", min ?: -1).apply(); arm(c) }
    fun setDelayMin(c: Context, min: Int) { prefs(c).edit().putInt("delay_min", min.coerceIn(0, 23 * 60)).apply(); arm(c) }

    /** The next run after [now]: today's (or tomorrow's) reset ([resetUtcMin] after midnight UTC) plus [delayMin] minutes. */
    fun nextAt(now: Long, delayMin: Int, resetUtcMin: Int): Long {
        val day = 24 * 3_600_000L
        val offset = resetUtcMin * 60_000L + delayMin * 60_000L
        var at = Math.floorDiv(now, day) * day + offset
        while (at <= now) at += day
        while (at - day > now) at -= day
        return at
    }

    /** When the next run is, in the phone's time ("16:10"), for the settings page. */
    fun describeNext(c: Context): String {
        val at = nextAt(System.currentTimeMillis(), delayMin(c), resetMin(c))
        return java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
    }

    private fun intent(c: Context, attempt: Int) = PendingIntent.getBroadcast(c, 30,
        Intent(c, ScheduleReceiver::class.java).putExtra(EXTRA_TRY, attempt),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Whether the alarm can be exact (Android 12+ needs the exact-alarm permission). */
    private fun exact(am: AlarmManager) = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    private fun setAlarm(am: AlarmManager, at: Long, pi: PendingIntent) {
        if (exact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // inexact: can come late
    }

    /** Set (or clear) the alarm for the next run. Called at start-up, on a reboot or app update, on a settings change and after each run. */
    fun arm(c: Context) {
        reportMissed(c)
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = intent(c, 0)
        am.cancel(pi)
        if (!enabled(c)) { prefs(c).edit().putLong("armed_at", 0).apply(); return }
        val at = nextAt(System.currentTimeMillis(), delayMin(c), resetMin(c))
        setAlarm(am, at, pi)
        if (prefs(c).getLong("armed_at", 0) != at) MacroLog.i("schedule: next ${macro(c)} run ${phoneTime(at)}${if (exact(am)) "" else " (inexact alarm: may come late)"}")
        prefs(c).edit().putLong("armed_at", at).apply()
    }

    private fun phoneTime(at: Long) = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))

    /** Whether the alarm set for [armedAt] should have gone off by [now] but never did (it went off last at [firedAt]). */
    fun missed(armedAt: Long, firedAt: Long, now: Long) = armedAt > 0 && firedAt < armedAt && now > armedAt + MISSED_AFTER_MS

    /** An alarm that never went off: say so once (news + notification). */
    fun reportMissed(c: Context) {
        val p = prefs(c)
        val armedAt = p.getLong("armed_at", 0)
        if (!enabled(c) || !missed(armedAt, p.getLong("fired_at", 0), System.currentTimeMillis())) return
        p.edit().putLong("fired_at", armedAt).apply() // reported
        PhoneEvents.init(c)
        val title = "Scheduled ${macro(c)} didn't run"
        val text = "Android never woke the app for the ${phoneTime(armedAt)} run. Battery saving can block it: " +
            "set ${GameProfile.current.appName}'s battery use to Unrestricted and keep it out of sleeping apps."
        MacroLog.i("schedule: $title — $text")
        PhoneEvents.add("schedule", title, text, ok = false)
        Alerts.schedule(c, title, text)
    }

    /** One line for macro_status: whether the schedule is on and when it runs next. */
    fun status(c: Context): String {
        if (!enabled(c)) return "daily schedule: off"
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val armedAt = prefs(c).getLong("armed_at", 0)
        return "daily schedule: ${macro(c)} next ${if (armedAt > 0) phoneTime(armedAt) else "not set (open the app)"}" +
            if (exact(am)) "" else " (inexact alarm)"
    }

    private fun retry(c: Context, attempt: Int) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        setAlarm(am, System.currentTimeMillis() + RETRY_MS, intent(c, attempt))
    }

    /** The alarm went off. */
    fun fire(c: Context, attempt: Int) {
        if (!enabled(c)) return
        if (attempt == 0) {
            prefs(c).edit().putLong("fired_at", System.currentTimeMillis()).apply()
            arm(c) // tomorrow's, before anything else can go wrong
        }
        PhoneEvents.init(c)
        val name = macro(c)
        fun problem(title: String, text: String) {
            MacroLog.i("schedule: $title — $text")
            PhoneEvents.add("schedule", title, text, ok = false)
            Alerts.schedule(c, title, text)
        }
        if (MacroService.capture == null) {
            problem("Scheduled $name didn't run", "Screen capture was off. Open ${GameProfile.current.appName} and allow screen capture to let it run tomorrow.")
            return
        }
        if (Runner.isRunning) {
            if (attempt < RETRIES) {
                MacroLog.i("schedule: ${Runner.current} is running; trying $name again in ${RETRY_MS / 60_000} minutes")
                retry(c, attempt + 1)
            } else problem("Scheduled $name didn't run", "${Runner.current ?: "Another macro"} was still running after ${RETRIES * RETRY_MS / 60_000} minutes.")
            return
        }
        MacroLog.i("schedule: starting $name")
        PhoneEvents.add("schedule", "Scheduled $name started", "", ok = true)
        c.startService(LastRun.intent(c, name, MacroParams.load(c, name)).putExtra(MacroService.EXTRA_SOURCE, "schedule"))
    }
}

/** The [DailySchedule] alarm; also sets it again after a reboot or an app update (Android drops alarms then). */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> DailySchedule.arm(context.applicationContext)
            else -> DailySchedule.fire(context.applicationContext, intent.getIntExtra("try", 0))
        }
    }
}
