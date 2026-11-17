# macro-forge

A toolkit to build and manage screen-reading macros for a mobile game, running on the Android
phone itself: screen capture to read the screen, an accessibility service to tap. No PC and no
root. Claude can drive the phone and write macros through the app's phone connector (an MCP
server).

macro-forge is the generic part. A game app uses it and adds what is its own:

- a `game.yaml` profile: the game's package, safety words (purchases, premium currency), macro
  groups, run report fields, the app's name and id;
- a game plugin (a Kotlin object implementing `macroforge.logic.GamePlugin`) with the game's own
  macro steps;
- its macros, screen templates, icons and art.

## Layout

- `android/core`: the Android library. The shell (`macroforge`: permissions, taps, screen
  capture, OCR, the phone connector, the UI, the updater, the macro runner) and the generic macro
  steps (`macroforge.logic`), which also ship on their own as a downloadable logic pack.
- `relay`: a Cloudflare Worker that gives the phone connector a public HTTPS address, so Claude
  can reach the phone as a custom connector (`relay/README.md`).
- `docs`: the game profile (`docs/game-profile.md`) and the logic pack (`docs/logic-pack.md`).
- `starter/game.yaml` and `android/starter`: a made-up game ("Pocket Quest") and its app, with a
  one-step game plugin (`starter.logic.StarterPlugin`). Copy them to start an app for your game.

Build and test it here: `gradle -p android testDebugUnitTest assembleDebug logicPack` (Android
SDK in `android/local.properties` or `ANDROID_HOME`).

## Using it in a game app

Add this repository as a git submodule (for example at `core/`) and include the module in the
app's Gradle settings:

```kotlin
include(":core")
project(":core").projectDir = file("../core/android/core")
```

In the app's `gradle.properties`, point the core at the game profile and the app module:

```properties
macroforge.game=../game.yaml
macroforge.app=:app
```

The app module depends on `project(":core")`, takes its `applicationId` from game.yaml's
`application_id`, and overrides the core's default resources (`ic_launcher`, `overlay_*`,
`app_name`) with its own.

## License

MIT
