package starter.logic

import macroforge.MacroSteps
import macroforge.logic.GamePlugin

/**
 * The starter game's own macro steps: one example step, `count: true`, which adds 1 to the
 * macro's variable `count`. A real game puts its fights, quests and routes here. game.yaml's
 * `plugin:` names this object, and it ships in the logic pack (no install for a change).
 */
object StarterPlugin : GamePlugin {
    override val actions = setOf("count")

    override fun label(action: String, arg: Any?): String? = when (action) {
        "count" -> "add 1 to count"
        else -> null
    }

    override fun explain(action: String, arg: Any?, env: MacroSteps.Env): List<MacroSteps.Step>? = null

    override fun newRun() = object : GamePlugin.Run {
        override fun run(macro: GamePlugin.Macro, action: String, step: Map<String, Any?>) {
            when (action) {
                "count" -> macro.vars["count"] = ((macro.vars["count"] as? Number)?.toInt() ?: 0) + 1
                else -> throw IllegalArgumentException("${macro.name}: unknown action $action")
            }
        }
    }
}
