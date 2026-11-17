package macroforge

import android.content.Context

/**
 * Global parameters: options many macros share, set once on System → Settings → Global parameters
 * instead of on one macro the others take them from (e.g. the party to use, the skill for each
 * party slot). The game declares them in game.yaml `globals:` (docs/game-profile.md); the macro
 * pack's copy wins over the one the app was built with, so a new global reaches the phone with the pack.
 *
 * A macro option follows one with `global: <key>` in its params. It then has one more value,
 * "global" ([VALUE], its default unless it says otherwise): while set to it, the option takes the
 * global parameter's value at run time ([fill]). Any other value is the macro's own and wins, so
 * each macro still keeps its own config.
 */
object Globals {
    /** The option value that means "use the global parameter". */
    const val VALUE = "global"
    private const val PREFS = "global_params"

    @Volatile private var app: Context? = null
    @Volatile private var cached: Pair<String, Map<String, Param>>? = null

    /** Remember the app, for [fill] (call before runs and when the app starts). */
    fun init(c: Context) { app = c.applicationContext }

    /** The global parameters the game declares: the macro pack's game.yaml, else the app's own. */
    fun defs(c: Context): Map<String, Param> {
        val text = runCatching { MacroLibrary.files(c).open("game.yaml").bufferedReader().use { it.readText() } }.getOrNull()
            ?: return GameProfile.current.globals
        cached?.let { (t, d) -> if (t == text) return d }
        val d = runCatching { GameProfile.parse(text).globals }.getOrNull() ?: return GameProfile.current.globals
        cached = text to d
        return d
    }

    /** What the user set on the Global parameters page (only the ones changed from their defaults). */
    fun saved(c: Context): Map<String, String> = MacroParams.decode(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("values", "").orEmpty())

    /** The value of the global parameter [key] now: as set, else its default; null when the game has no such global. */
    fun value(c: Context, key: String): String? {
        val p = defs(c)[key] ?: GameProfile.current.globals[key] ?: return null
        return saved(c)[key] ?: p.default
    }

    fun set(c: Context, key: String, value: String) {
        val all = saved(c) + (key to value)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("values", MacroParams.encode(all)).apply()
        MacroLog.i("global parameter $key = $value")
    }

    /**
     * [values] with every option of [params] that is set to "global" replaced by its global
     * parameter's value (one whose global the game doesn't have: its first choice, else empty).
     */
    fun fill(params: Map<String, Param>, values: Map<String, String>): Map<String, String> {
        if (values.none { it.value.trim() == VALUE }) return values
        val c = app
        return values.mapValues { (k, v) ->
            if (v.trim() != VALUE) return@mapValues v
            val p = params[k]
            val g = p?.global ?: return@mapValues v
            c?.let { value(it, g) } ?: GameProfile.current.globals[g]?.default ?: p.choices.firstOrNull { it != VALUE } ?: ""
        }
    }

    /** How the app shows the "global" choice of [p]: "Global (4)". */
    fun label(c: Context, p: Param): String = p.global?.let { g -> value(c, g)?.let { "Global ($it)" } } ?: "Global"
}
