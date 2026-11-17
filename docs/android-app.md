# The Android app

The core runs a game's macros on the phone **by itself**: no PC, no ADB, no root.

- **Screen capture:** MediaProjection (Android asks "Start recording?" once per session).
- **Input:** an accessibility service dispatches taps, slide-and-hold walking and Back.
- **Vision:** OpenCV template matching on the `library.yaml` + PNG templates of the macro pack;
  ML Kit OCR for text.
- **Macros are downloaded, not built in:** the app installs the macro pack (macros, templates,
  data) the game app's CI publishes, and updates it by itself (see *The macro library*).
- **Claude:** the user's own Claude chat can play or write macros through the phone connector.
- **Floating controls** over the game: the selected macro (tap it to pick another), start /
  pause / stop / restart, shown while screen capture is on.

## Screens

A sidebar (≡ at the top left) switches between four pages, all in `MainActivity.kt`:

- **Home** (`HomePage.kt`): what the app is (game.yaml `about`), what's still missing (taps,
  capture), the selected macro, how to use the app, and links (GitHub, relay guide, releases).
- **Macros** (`MacrosPage.kt`): a search box, tabs "In use" plus one per game.yaml group. Each
  macro has a switch to choose whether you use it. Tap a macro for "How it works" (with the
  macros it runs opened up, `MacroSteps.kt`), its options and **Select this macro**: the
  floating buttons start, pause and stop it. Options are kept per macro (`MacroParams`).
- **Statistics** (`StatsPage.kt`, data in `RunStats.kt`): every macro run on this phone
  (`files/stats/runs.jsonl`: start, end, stop reason, counters, who started it, the routine it
  was a step of, and the run reports written to `runs.log`, shown as game.yaml `reports:` says).
  Totals, runs and minutes per day, time by macro; per macro its totals, run lengths, stop
  reasons, items and rewards read. "Clear" removes all statistics or one macro's.
- **System** (`SystemPage.kt`), in tabs: Setup (taps, screen capture), Claude (the phone
  connector and what Claude may do), Settings, Updates and Log.

From adb: `am start -n <application_id>/macroforge.MainActivity --es page macros`, or
`--es run <macro> --es params "name=value"` to start a macro; `adb logcat -s MacroForge` for
the log.

## The phone connector

The **System → Claude** section turns the app into a remote MCP server that the user adds in
claude.ai as a custom connector, so their own Claude chat (any plan, any Claude app) can see
and control the game. A phone has no public address, so the app keeps a WebSocket open to the
user's relay, a small Cloudflare Worker on their own account ([relay/README.md](../relay/README.md)).

- `PhoneConnector.kt`: the socket (reconnects with backoff), turns itself off after 30 minutes
  without tool calls, and maps the connector's tools (screenshot, zoom, tap, swipe, back, wait,
  walk, read_text, find_template, list_templates, list_macros, read_macro, start_macro,
  macro_status, stop_macro, save_template, save_macro, launch_game, floating_controls,
  wake_screen, update_app, update_macros, delete_macro) onto `PhoneTools`. tap and swipe fold
  the floating buttons first when their window covers the point.
- `ScreenWake.kt`: turns the screen on and keeps it on while Claude or a macro drives the game.
  An all-black capture is never taken for "the game isn't in front": screenshots say so, taps on
  a black frame are refused, and a macro stops after 2 minutes of black.
- `PhoneTools.kt`: the tools, behind the safety layer (`ClaudeSafety.kt`, enforced in code, not
  just the prompt): taps only while the game is in front; every tap goes through
  `Context.tap`'s purchase guard; screens offering a purchase are refused (a second attempt
  turns the connector off); taps on sell/discard/release/exchange/... or on screens naming the
  premium currency (game.yaml `safety`) ask the user on the phone (`AskOverlay.kt`, denied after
  2 minutes because connector tool calls time out at 240 s). Macros run in the background
  (`start_macro` / `macro_status`) for the same reason; library macro names can't be replaced.
- `McpServer.kt`: the MCP JSON-RPC (initialize, ping, tools/list, tools/call), unit tested.
- `ClaudePrompt.kt`: what Claude is told about the game (game.yaml `claude`).
- Tokens: a phone token (app → relay) and a connector token (Claude → relay), 256-bit random,
  stored encrypted with an Android Keystore AES key (`ClaudeSettings.kt`), never logged.

## Updates and releases

- **Dev builds** (`0.0.N-dev`): the game app's CI publishes every pushed branch's APK and logic
  pack as the pre-release `dev-<branch>` (notes carry `shell:` and `commit:`). A dev build
  follows its branch (`BuildConfig.BRANCH`, normally `main`; System → Updates → *Build to
  follow* lists main and open pull requests' builds): it checks every 3 minutes while the
  connector is on and idle, on `update_app`, and on *Install the newest build*. When only the
  logic changed it loads the logic pack with no install ([logic-pack.md](logic-pack.md));
  otherwise it installs the APK. If Play Protect blocks an unattended install, the app taps
  *Got it* (never *Install anyway*) and doesn't retry that version. After any install, screen
  capture and the connector stay off until the user turns them on.
- **Releases** (`vX.Y.Z`): a GitHub Release with the signed APK. Release builds check for a newer
  release when they open (at most every 6 hours). They don't load logic packs, so stay on dev
  builds to follow `main`. versionCode is derived from the version (1.2.3 → 10203).
- **Signing:** one permanent key for every build, or Android refuses updates. Keep a backup.
- **Workflows:** a game app calls this repo's reusable workflows instead of copying them:
  `app-build.yml` (tests, dev build, logic pack), `macro-pack.yml` (the `macros-<branch>` pack)
  and `app-release.yml` (a release). Each file's header shows the calling job. Signing comes from
  the secrets `ANDROID_KEYSTORE_B64` (the .p12 key, base64) and `ANDROID_KEYSTORE_PASSWORD`.

## The macro library

Macros are **not** in the APK: the game app's CI packs `macros/<group>/*.yaml` with templates,
data and game.yaml on every push and publishes `macros.zip` as the pre-release
`macros-<branch>`. The app (`MacroLibrary.kt`) follows the same branch as its own updates (a
release build: `main`), checks at most every 15 minutes (on opening the app, turning on capture,
and while the connector is idle) and swaps a newer pack in between runs. So a macro fix reaches
phones with a push, without an app release. The format is in
[writing-macros.md](writing-macros.md).

Users don't add macros themselves. Claude can save **custom** macros through the connector
(`save_macro`, listed under Custom) as drafts; one that fits a group is then published to the
library and removed from the phone (`delete_macro`). `update_macros` installs the newest pack
now.

## Templates captured with Claude

The connector's `save_template` saves a box of the current screen as a template in the app's
storage, usable in macros by name: `found('my_button')`, `tap: my_button`.
