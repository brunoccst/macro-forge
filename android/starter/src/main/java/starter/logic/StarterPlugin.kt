package starter.logic

import macroforge.MacroSteps
import macroforge.logic.GamePlugin

/**
 * The starter game's own macro steps: one example step, `roll_die: true`, which sets the
 * macro's variable `roll` to a number from 1 to 6. A real game puts its fights, quests and
 * routes here. game.yaml's `plugin:` names this object, `plugin_steps:` lists its steps, and it
 * ships in the logic pack (no install for a change).
 */
object StarterPlugin : GamePlugin {
    override val actions = setOf("roll_die")

    override fun label(action: String, arg: Any?): String? = when (action) {
        "roll_die" -> "roll a die"
        else -> null
    }

    override fun explain(action: String, arg: Any?, env: MacroSteps.Env): List<MacroSteps.Step>? = null

    override fun newRun() = object : GamePlugin.Run {
        override fun run(macro: GamePlugin.Macro, action: String, step: Map<String, Any?>) {
            when (action) {
                "roll_die" -> macro.vars["roll"] = (1..6).random()
                else -> throw IllegalArgumentException("${macro.name}: unknown action $action")
            }
        }
    }
}
