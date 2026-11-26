package macroforge

/**
 * How a routine (`steps:`) goes on after one of its steps: every step runs, whatever the one
 * before it did, unless a step the routine marks `required:` fails. Then the steps after it
 * are skipped (they'd only time out one after another without what it does, e.g. a game that
 * didn't open), except the ones marked `always:` (e.g. the one that turns the game off).
 * A step the user switched off ([switchKey]) is skipped and listed as "skipped (off)".
 */
object Routine {
    /** A picked step whose option says this runs nothing. */
    const val NONE = "none"

    /**
     * The routine option that switches a step on or off ("Include <step>", on by default): its key
     * is `include_` and the step's macro name, or its option name for a picked step ("{dungeon}").
     */
    fun switchKey(macro: String) = "include_" + macro.trim('{', '}', ' ')

    /** Whether the user switched [step] off in the routine's [options]. */
    fun isOff(step: RoutineStep, options: Map<String, String>) = options[switchKey(step.macro)]?.trim()?.lowercase() == "false"

    /**
     * The macro and the options a routine step runs with, the routine's [options] filled in:
     * "{dungeon}" becomes the macro picked in the option dungeon, and so do "{...}" in the step's
     * params. Null: nothing picked (empty or [NONE]), the step is left out.
     */
    fun fill(step: RoutineStep, options: Map<String, String>): Pair<String, Map<String, String>>? {
        fun f(t: String) = Regex("""\{([A-Za-z0-9_.\-]+)\}""").replace(t) { options[it.groupValues[1]]?.trim() ?: it.value }
        val name = f(step.macro).trim()
        if (name.isEmpty() || name == NONE) return null
        return name to step.params.mapValues { f(it.value) }
    }

    /** A step's stop reason that counts as failed: it got stuck, crashed, or said `stop: "failed: ..."`. */
    fun failed(reason: String) = RunStats.classify(reason).let { it == RunStats.Outcome.STUCK || it == RunStats.Outcome.ERROR }

    /**
     * Whether [step] runs, given why the routine is skipping (null: it isn't): the [always]
     * steps run anyway.
     */
    fun runs(step: String, skipping: String?, always: Set<String>) = skipping == null || step in always

    /**
     * Why the routine skips its remaining steps after [step] ended on [reason] (an unknown
     * macro: null [reason]); null = it goes on.
     */
    fun skipAfter(step: String, reason: String?, required: Set<String>): String? = when {
        step !in required -> null
        reason == null -> "$step is not installed"
        failed(reason) -> "$step failed"
        else -> null
    }

    /**
     * Whether the routine runs its [recovery] macro (game.yaml `recovery_macro`, "" = none) after
     * [step] ended on [reason]: only when the step failed, the routine isn't already skipping, and
     * [moreSteps] still follow. It never runs after itself.
     */
    fun recoversAfter(step: String, reason: String, skipping: String?, recovery: String, moreSteps: Boolean) =
        recovery.isNotEmpty() && step != recovery && skipping == null && moreSteps && failed(reason)
}
