package macroforge.logic

import macroforge.*
import macroforge.FlowScenario.Companion.list
import macroforge.FlowScenario.Companion.map
import macroforge.FlowScenario.Companion.mapOrNull
import macroforge.FlowScenario.Companion.runTarget
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Runs a YAML macro's steps (see the format at the top of FlowScenario in Flow.kt). Part of the
 * logic pack: a new generic step or expression function goes here and in [STEPS], and reaches the
 * phone without an app install. Every other step belongs to the game's [plugin] ([GamePlugin]).
 */
class FlowRun(private val scenario: FlowScenario, private val plugin: GamePlugin = Entry.PLUGIN) : FlowEngine {
    private val name = scenario.name
    private val spec = scenario.spec
    private val handlers = map(spec["handlers"]).entries.associate { (k, v) -> k.toString() to list(v) }
    private val vars = HashMap<String, Any?>()
    private var lastView: Mat? = null
    private var game = plugin.newRun()
    // signs that a commercial is over (ad_left(), screen_still(), ad_warning())
    private val adBar = AdSigns.Bar()
    private var countdownDue = -1.0
    private var stillView: Mat? = null
    private var stillSince = 0.0
    // the screen's text (OCR) for this frame: every text_has() of one pass shares one read
    private var text: List<Ocr.Line>? = null
    private var textOf: Frame? = null

    /** What the plugin's steps see of this macro run. */
    private fun macro(c: Context) = object : GamePlugin.Macro {
        override val ctx: Context = c
        override val name: String = this@FlowRun.name
        override val vars: MutableMap<String, Any?> = this@FlowRun.vars
        override fun value(v: Any?): Any? = this@FlowRun.value(c, v)
        override fun text(v: Any?): String = this@FlowRun.text(c, v)
    }

    override fun setup(ctx: Context) {
        vars.clear()
        lastView?.release(); lastView = null
        stillView?.release(); stillView = null
        adBar.reset(); countdownDue = -1.0; stillSince = 0.0
        text = null; textOf = null
        game = plugin.newRun()
        for ((k, v) in ctx.params) vars[k] = FlowScenario.typed(v)
        runActions(ctx, list(spec["setup"]))
    }

    override fun onScreen(ctx: Context, screen: String) = runActions(ctx, handlers[screen].orEmpty())
    override fun onUnknown(ctx: Context) = runActions(ctx, list(spec["unknown"]))

    // --- actions ---------------------------------------------------------------
    private fun runActions(ctx: Context, actions: List<Any?>) {
        for (a in actions) runAction(ctx, mapOrNull(a) ?: throw IllegalArgumentException("$name: action must be a map, got $a"))
    }

    private fun runAction(ctx: Context, a: Map<String, Any?>) {
        // anything but bookkeeping may change the screen: read its text again next time
        if (a.keys.none { it in BOOKKEEPING }) textOf = null
        when {
            "if" in a -> runActions(ctx, list(if (truthy(ctx, a["if"])) a["then"] else a["else"]))
            "tap" in a -> when (val t = a["tap"]) {
                is String -> ctx.tap(t)
                else -> ctx.tap(point(ctx, t))
            }
            "tap_exact" in a -> ctx.tapExact(point(ctx, a["tap_exact"]))
            "drag" in a -> {
                val m = map(a["drag"])
                ctx.drag(point(ctx, m["from"]), point(ctx, m["to"]), num(ctx, m["ms"] ?: 300).toLong())
            }
            "walk" in a -> {
                // walking is a virtual stick: press mid-screen and slide toward the direction
                val m = map(a["walk"])
                val d = value(ctx, m["dir"])
                val (dx, dy) = when (Expr.str(d)) { "up" -> 0.0 to -0.08; "down" -> 0.0 to 0.08; else -> 0.06 * Expr.num(d) to 0.0 }
                ctx.drag(Point(0.5, 0.55), Point(0.5 + dx, 0.55 + dy), num(ctx, m["ms"] ?: 1500).toLong())
            }
            "back" in a -> ctx.back()
            "home" in a -> AdEscape.home()
            // a commercial that won't close (no X, Back ignored): Home, then the game's own screen
            "leave_ad" in a -> AdEscape.leave(ctx)
            // the last resort: swipe the game away in recent apps; sets `closed`
            "force_close" in a -> vars["closed"] = AdEscape.forceClose(ctx)
            "wait" in a -> ctx.wait(num(ctx, a["wait"]))
            "launch" in a -> ctx.launchApp(Expr.str(value(ctx, a["launch"])))
            "log" in a -> MacroLog.i(text(ctx, a["log"]))
            "status" in a -> MacroLog.status("$name: ${text(ctx, a["status"])}")
            "set" in a -> map(a["set"]).forEach { (k, v) -> vars[k.toString()] = value(ctx, v) }
            "count" in a -> ctx.count(a["count"].toString())
            // what the macro got, for the outcome message: a reward text ("Gold Coin x5"), or {item, n}
            "got" in a -> when (val g = a["got"]) {
                is Map<*, *> -> map(g).let { ctx.got(text(ctx, it["item"]), (it["n"] ?: 1).let { n -> num(ctx, n).toInt() }) }
                else -> ctx.gotText(text(ctx, g))
            }
            "stop" in a -> ctx.stop(text(ctx, a["stop"]))
            "repeat" in a -> {
                val m = map(a["repeat"])
                repeat(num(ctx, m["times"]).toInt()) { runActions(ctx, list(m["do"])) }
            }
            "reset_view" in a -> { lastView?.release(); lastView = null }
            "run" in a -> {
                val target = runTarget(a["run"])
                val m = mapOrNull(a["run"])
                val sub = ctx.findMacro(target) ?: throw IllegalArgumentException("$name: run: unknown macro '$target'")
                val given = map(m?.get("params")).entries.associate { (k, v) -> k to Expr.str(value(ctx, v)) }
                val until = list(m?.get("until")).map { it.toString() }
                vars["result"] = Runner.runInside(sub, given, ctx, until)
            }
            // a step of the game's own (fights, quests, ...)
            else -> plugin.actions.firstOrNull { it in a }?.let { game.run(macro(ctx), it, a) }
                ?: throw IllegalArgumentException("$name: unknown action ${a.keys}")
        }
    }

    // --- builtins ----------------------------------------------------------------
    private fun viewMoved(ctx: Context): Boolean {
        val g = ctx.frame?.gray ?: return true
        val roi = g.submat(Rect(0, (g.rows() * 0.25).toInt(), (g.cols() * 0.8).toInt(), (g.rows() * 0.55).toInt()))
        val small = Mat()
        Imgproc.resize(roi, small, Size(160.0, 60.0), 0.0, 0.0, Imgproc.INTER_AREA)
        roi.release()
        val prev = lastView
        lastView = small
        if (prev == null) return true
        val diff = Mat()
        Core.absdiff(small, prev, diff)
        val m = Core.mean(diff).`val`[0]
        diff.release(); prev.release()
        return m > 4.0
    }

    /** The screen's text, read once per frame (and again after any step that may change it). */
    private fun texts(ctx: Context): List<Ocr.Line> {
        val f = ctx.frame
        text?.let { if (f != null && f === textOf) return it }
        return ctx.readText().also { text = it; textOf = f }
    }

    /**
     * Where the picture is across the frame: a portrait commercial shows pillar-boxed in the
     * landscape capture (black on both sides), so its bar is in the middle column.
     */
    private fun picture(g: Mat): Pair<Int, Int> {
        val cols = Mat()
        Core.reduce(g, cols, 0, Core.REDUCE_AVG, org.opencv.core.CvType.CV_32F)
        val w = g.cols()
        val means = FloatArray(w)
        cols.get(0, 0, means)
        cols.release()
        val x0 = means.indexOfFirst { it > 6f }.coerceAtLeast(0)
        return if (x0 > w * 0.08 && x0 < w * 0.45) x0 to w - x0 else 0 to w
    }

    /** Seconds until the commercial on screen is over (0: it is), from its progress bar or countdown text; -1: no sign. */
    private fun adLeft(ctx: Context): Double {
        val now = System.currentTimeMillis() / 1000.0
        val bar = ctx.frame?.gray?.let { g ->
            val (x0, x1) = picture(g)
            val h = g.rows()
            val band = (h * 0.2).toInt()
            val buf = ByteArray(x1 - x0)
            val rows = ((0 until band) + (h - band until h)).map { y ->
                g.get(y, x0, buf)
                y to IntArray(buf.size) { buf[it].toInt() and 0xff }
            }
            adBar.update(now, rows)
        } ?: -1.0
        // "Reward in 12 seconds": remembered, since the text goes once it is over
        val cd = AdSigns.countdown(texts(ctx).map { it.text })
        if (cd >= 0) countdownDue = now + cd + 1
        else if (countdownDue > 0 && now - countdownDue > 15) countdownDue = -1.0
        val byText = if (countdownDue > 0) (countdownDue - now).coerceAtLeast(0.0) else -1.0
        return listOf(bar, byText).filter { it >= 0 }.minOrNull() ?: -1.0
    }

    /** Seconds the picture hasn't changed (a commercial's video over, its end card up); 0 when it just did. */
    private fun screenStill(ctx: Context): Double {
        val now = System.currentTimeMillis() / 1000.0
        val g = ctx.frame?.gray ?: return 0.0
        // the left 80 %: our own floating buttons and log line are at the right
        val roi = g.submat(Rect(0, 0, (g.cols() * 0.8).toInt(), g.rows()))
        val small = Mat()
        Imgproc.resize(roi, small, Size(64.0, 36.0), 0.0, 0.0, Imgproc.INTER_AREA)
        roi.release()
        val prev = stillView
        stillView = small
        if (prev == null) { stillSince = now; return 0.0 }
        val diff = Mat()
        Core.absdiff(small, prev, diff)
        val m = Core.mean(diff).`val`[0]
        diff.release(); prev.release()
        if (m > 1.5) stillSince = now
        return now - stillSince
    }

    // --- values --------------------------------------------------------------------
    private fun env(ctx: Context): Expr.Env {
        val macro = name
        return object : Expr.Env {
        override fun variable(name: String): Any? = when {
            name.startsWith("stats.") -> (ctx.stats[name.removePrefix("stats.")] ?: 0).toDouble()
            name in vars -> vars[name]
            else -> throw IllegalArgumentException("$macro: unknown name '$name'")
        }

        override fun call(name: String, args: List<Any?>): Any? = when (name) {
            "found" -> ctx.find(Expr.str(args[0])) != null
            "captured" -> ctx.vision.library.templates[Expr.str(args[0])]?.available == true
            "game_in_front" -> ctx.frame?.portraitAppInFront != true
            // a commercial in front: the game's package, but an activity other than the game's own
            "ad_in_front" -> InputService.instance?.let { s ->
                val game = Game.of(ctx)
                s.foreground == game.packageName && s.foregroundActivity.let { a -> a != null && a != game.activity }
            } ?: false
            // the game's own activity in front (no commercial, no other app), as last reported by Android
            "game_screen" -> InputService.instance?.let { s -> Game.of(ctx).let { g -> s.foreground == g.packageName && s.foregroundActivity == g.activity } } ?: false
            "view_moved" -> viewMoved(ctx)
            "now" -> System.currentTimeMillis() / 1000.0
            // the phone is on Wi-Fi (or another unmetered network), e.g. before letting the game download a big update
            "on_wifi" -> InputService.instance?.let { s -> runCatching {
                val cm = s.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.getNetworkCapabilities(cm.activeNetwork)?.let { c ->
                    c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                } ?: false
            }.getOrDefault(false) } ?: false
            // on-screen text (OCR) contains this, loosely (case and punctuation ignored)
            "text_has" -> Ocr.norm(Expr.str(args[0])).let { want -> texts(ctx).any { Ocr.norm(it.text).contains(want) } }
            // the text on screen (OCR), optionally only between heights y0..y1, for a log line (e.g. what a reward was)
            "screen_text" -> {
                val y0 = if (args.isNotEmpty()) Expr.num(args[0]) else 0.0
                val y1 = if (args.size > 1) Expr.num(args[1]) else 1.0
                texts(ctx).filter { it.center.y in y0..y1 }.joinToString(" ") { it.text }.take(160)
            }
            // a list row still to do: the height (0..1) of the top-most text line containing `anchor`
            // (between heights y0..y1) with no text containing `mark` on the same line, else -1.
            // E.g. text_row_without('utc', 'opened', 0.15, 0.85): the first inbox item not opened yet.
            "text_row_without" -> {
                val anchor = Ocr.norm(Expr.str(args[0]))
                val mark = Ocr.norm(Expr.str(args[1]))
                val y0 = if (args.size > 2) Expr.num(args[2]) else 0.0
                val y1 = if (args.size > 3) Expr.num(args[3]) else 1.0
                val lines = texts(ctx)
                val marks = lines.filter { Ocr.norm(it.text).contains(mark) }.map { it.center.y }
                lines.filter { it.center.y in y0..y1 && Ocr.norm(it.text).contains(anchor) }
                    .sortedBy { it.center.y }
                    .firstOrNull { l -> marks.none { kotlin.math.abs(it - l.center.y) < 0.025 } }
                    ?.center?.y ?: -1.0
            }
            // a commercial's own signs that it is over (see AdSigns): seconds left by its progress bar
            // or countdown text, 0 = over, -1 = no sign; how long the picture has been still (its end
            // card); its "Close video? You will lose your reward" box
            "ad_left" -> adLeft(ctx)
            "screen_still" -> screenStill(ctx)
            "ad_warning" -> AdSigns.forfeitWarning(texts(ctx).map { it.text })
            "pick" -> args[Expr.num(args[0]).toInt().coerceIn(1, args.size - 1)]
            "abs" -> kotlin.math.abs(Expr.num(args[0]))
            else -> throw IllegalArgumentException("$macro: unknown function $name()")
        }
        }
    }

    /** A YAML value: strings are expressions, numbers/booleans are themselves. */
    private fun value(ctx: Context, v: Any?): Any? = when (v) {
        is String -> Expr.eval(v, env(ctx))
        is Int, is Long -> (v as Number).toDouble()
        else -> v
    }

    private fun num(ctx: Context, v: Any?) = Expr.num(value(ctx, v))
    private fun truthy(ctx: Context, v: Any?) = Expr.truthy(value(ctx, v))

    /** "text with {expressions}" */
    private fun text(ctx: Context, v: Any?): String =
        Regex("""\{([^}]+)\}""").replace(v.toString()) { Expr.str(Expr.eval(it.groupValues[1], env(ctx))) }

    private fun point(ctx: Context, v: Any?): Point = when (v) {
        is List<*> -> Point(num(ctx, v[0]), num(ctx, v[1]))
        is Map<*, *> -> map(v).let { Point(num(ctx, it["x"]), num(ctx, it["y"])) }
        else -> throw IllegalArgumentException("$name: not a point: $v")
    }


    companion object {
        /** The generic steps: any game's macros can use them. Keep in step with [runAction]. */
        /** Steps that never change the screen (the screen's text read before them still holds). */
        private val BOOKKEEPING = setOf("if", "set", "log", "status", "count", "got", "stop", "reset_view")

        val STEPS = setOf("if", "tap", "tap_exact", "drag", "walk", "back", "home", "leave_ad", "force_close", "wait", "launch", "log",
            "status", "set", "count", "got", "stop", "repeat", "reset_view", "run")
    }
}
