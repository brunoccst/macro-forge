package macroforge

/**
 * How a routine (`steps:`) goes on after one of its steps: every step runs, whatever the one
 * before it did, unless a step the routine marks `required:` fails. Then the steps after it
 * are skipped (they'd only time out one after another without what it does, e.g. a game that
 * didn't open), except the ones marked `always:` (e.g. the one that turns the game off).
 */
object Routine {
    /** A picked step whose option says this runs nothing. */
    const val NONE = "none"

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
}
