package macroforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Global parameters: game.yaml globals:, options that follow them, and their values at run time. */
class GlobalsTest {
    private val profile = GameProfile.parse("""
        name: Some Game
        globals:
          team: {default: current, choices: [current, 1, 2, 3], help: the team to use}
          skill_1: {default: auto, choices: [auto, 1, 2]}
    """.trimIndent())

    private fun macro(params: String) = FlowScenario.parse("name: m\nparams:\n$params\nhandlers: {}\n", "m.yaml")

    @Test fun theProfileDeclaresThem() {
        assertEquals(listOf("team", "skill_1"), profile.globals.keys.toList())
        assertEquals("current", profile.globals.getValue("team").default)
        assertEquals(listOf("current", "1", "2", "3"), profile.globals.getValue("team").choices)
        assertEquals("the team to use", profile.globals.getValue("team").help)
        assertEquals(emptyMap<String, Param>(), GameProfile.parse("name: x").globals)
    }

    @Test fun anOptionThatFollowsOneStartsAsGlobal() {
        val m = macro("  skill_1: {choices: [auto, 1, 2], global: skill_1}\n  team: {default: 2, global: team}\n  other: {default: 3}")
        assertEquals(Globals.VALUE, m.params.getValue("skill_1").default)
        assertEquals("skill_1", m.params.getValue("skill_1").global)
        // its own default wins when it gives one
        assertEquals("2", m.params.getValue("team").default)
        assertNull(m.params.getValue("other").global)
    }

    @Test fun globalIsReplacedAndOwnValuesStay() {
        val params = mapOf("a" to Param(Globals.VALUE, "", listOf("auto", "1"), global = "nope"), "b" to Param("1", ""))
        // no app and no such global: the option's first choice
        assertEquals(mapOf("a" to "auto", "b" to "1"), Globals.fill(params, mapOf("a" to "global", "b" to "1")))
        assertEquals(mapOf("a" to "1"), Globals.fill(params, mapOf("a" to "1")))
    }

    @Test fun innerOptionsKeepTheirGlobal() {
        val inner = macro("  skill_1: {choices: [auto, 1, 2], global: skill_1}")
        val outer = FlowScenario.parse("name: outer\nuses: [m]\nhandlers: {}\n", "outer.yaml")
        InnerParams.link(listOf(inner, outer))
        assertEquals("skill_1", outer.params.getValue("m.skill_1").global)
        assertEquals(Globals.VALUE, outer.params.getValue("m.skill_1").default)
    }
}
