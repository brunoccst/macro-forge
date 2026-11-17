package starter

import macroforge.GameProfile
import macroforge.Logic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The core finds the game named by game.yaml: its profile and its plugin's steps. */
class StarterPluginTest {
    @Test fun profileIsBuiltIn() {
        assertEquals("Pocket Quest", GameProfile.current.name)
        assertEquals("starter.logic.StarterPlugin", GameProfile.current.plugin)
    }

    @Test fun pluginStepsJoinTheGenericOnes() {
        val actions = Logic.current().entry.actions
        assertTrue("tap" in actions)
        assertTrue("roll_die" in actions)
        assertEquals("roll a die", Logic.current().entry.label("roll_die", true))
    }
}
