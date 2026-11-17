# The game profile (`game.yaml`)

`game.yaml` says which game an app plays (the starter's is `starter/game.yaml`). The core asks
the profile instead of naming a game, so the same code serves any game with its own profile.

| Key | What it is |
|---|---|
| `name` | The game's name, also the name of its card in recent apps (force close) |
| `package` | The game's Android package |
| `activity` | The game's own activity. The same package with another activity in front is a commercial (`ad_in_front()`, `leave_ad`) |
| `repo` | The GitHub repo that hosts the app's builds and packs |
| `application_id` | The app's own Android id (its package on the phone). Changing it makes a new app: the old one is not updated |
| `plugin` | The Kotlin object (implements `GamePlugin`) with the game's own macro steps; it ships in the logic pack |
| `safety.purchase_words` | Words that mean an offer to pay: never tapped |
| `safety.premium_words` | Words that mean premium currency: taps there need the user's OK |
| `groups` | The macro groups in tab order: `id` (a macro's `group:` and its folder in `macros/`), `title`, `icon` (`res/drawable/ic_<icon>.xml`) |

## How it reaches the phone

The build bakes `game.yaml` into the app (`BuildConfig.GAME_YAML`, read by `GameProfile.kt`)
and puts its `package` in the manifest (`${gamePackage}`, so the app may see and open the game).
The app's own code asks `GameProfile.current`. The file is part of the app's fingerprint
(`SHELL_ID`), so changing it means a new app build.

The app's macro pack can carry a copy too. The logic pack reads it through
`macroforge/logic/Game.kt` (`Game.of(ctx)`), so a fix to, say, the safety words reaches the logic with the
next macro pack. Without a pack copy the logic uses the app's own.

## Other keys

| Key | What it is |
|---|---|
| `app_name`, `tagline`, `about` | The app's name, the line under it and the Home page's text |
| `safety.shop_screen`, `shop_close`, `shop_safe` | The purchase guard: while the shop template shows, macros may only tap `shop_safe` templates |
| `relay_guide` | The web address of the relay setup guide the app links to (default: the repo's `relay/README.md`) |
| `claude` | What Claude is told about the game: extra how-to lines, premium-currency examples, the example macro, a reward text example, extra macro-format lines |

## Run reports

Macros write one report per round to `files/runs.log` . The
outcome card and the Statistics page show them by `reports:`: its `title` names the Statistics
section, and each field says how a key shows. A switch (true/false) shows its `show` text when on
and its `total` counts the reports with it on; a number shows `show` with `{value}` and its total
adds up (`of:` gives "x of y"); a text's total counts each value (`{Value}`). `card:` also puts the
total on the outcome card, and `duration: true` shows seconds as a time.

## Rules for code

- App code never names the game: use `GameProfile.current`. Logic code uses `Game.of(ctx)`.
- A new game-specific value goes in `game.yaml` and in `GameProfile`.

## The game plugin

The profile holds values; the game's own *code* sits behind `macroforge/logic/GamePlugin.kt`. The generic
steps (`FlowRun.STEPS`: tap, walk, run, leave_ad, ...) know nothing about the game, and
`FlowRun` hands every other step to the plugin that `Entry.PLUGIN` names. The starter's plugin is
`starter.logic.StarterPlugin` (one example step, `count`). A plugin gives its steps (`actions`), the basic macros they run in code
(`stepUses`, so outer macros still take over those macros' options), their "How it works" words
(`explain`) and a fresh `Run` per macro run for its state. A game with only YAML macros uses
`GamePlugin.None`.
