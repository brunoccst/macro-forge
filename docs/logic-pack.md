# The logic pack: app changes without an install

Installing a new APK needs Play Protect's approval on the phone every time, so an unattended
test of an app change would stall at the first install. To avoid that, the app is split in two:

- **The shell** is the installed APK. It holds the permissions, taps, screen capture, OCR, vision,
  the phone connector, the UI, the updater and the macro runner. It is this repository's
  `android/core` (Kotlin package `macroforge`), plus the game app's resources (icons, name).
- **The logic** is two packages: the generic macro steps in `macroforge.logic` (`FlowRun`), and
  the game's plugin package, which game.yaml's `plugin:` names (the starter's is
  `starter.logic`). Both are built into the APK as a fallback, and CI also ships them on their
  own as `logic.zip`.

## How a build reaches the phone

`android.yml` builds the APK and `logic.zip` (`gradle -p android logicPack`) from the same
compile. It attaches both to the branch's `dev-<branch>` pre-release, and its notes carry
`shell: <SHELL_ID>` and `commit: <sha>`.

A dev build checks its branch every 3 minutes while the connector is on and idle. Claude's
`update_app` tool and System → Updates check it on demand.

- **Same shell** (the notes' `shell` equals the installed app's `BuildConfig.SHELL_ID`): the app
  downloads `logic.zip` and loads it with a class loader that takes the `logic` package from the
  pack and everything else from the app. There is no install, no restart and no Play Protect
  prompt, and the next macro run uses the new logic. A macro that is already running keeps the
  logic it started with.
- **Shell changed:** the APK has to be installed as before, which needs the user's approval
  while Play Protect blocks it. A pack built for another shell is never loaded.

`SHELL_ID` is a hash of every shell file, plus the Gradle build files and game.yaml. Any change to them, even
a comment, means a new install.

## Rules for code

- **Overnight or unattended tests:** keep the change inside the two `logic` packages. If the shell must change,
  batch those changes and ask the user for one install.
- **Shell code never names a class in `logic`.** It reaches the logic only through the
  `LogicEntry` / `FlowEngine` interfaces (`Logic.kt`), and creates `logic.Entry` by name.
  `LogicPackTest` enforces this.
- **Logic code may use anything in the shell.** The shell is its API, and changing a shell
  signature that the logic uses means a new app build anyway.
- **A new macro step:** a generic one (any game could use it) goes in `FlowRun.runAction` and
  `FlowRun.STEPS`; one that knows the game goes in the game plugin (`actions`
  and `run`, its basic macros in `stepUses`, its "How it works" words in `explain`). `Entry`
  lists both. Macros that use it stop being "unsupported" as soon as the pack loads.
- **Panning a map:** a macro step that reads a map larger than the screen uses
  `logic/MapSweep.kt` (the corner first, then row by row until the view stops moving, drags only
  inside a safe area, three different starts before an edge counts). It keeps its own text
  matching so it ships with the pack.
- **Generic logic never names game code.** `FlowRun`, `GamePlugin`, `Game` and `AdEscape` work for
  any game; a game app should test that they never use its own classes.
- **Stored state:** the loaded pack lives in the app's files (`files/logic/current`). If it fails
  to load, the app logs why and runs the built-in logic.
- **A pack that breaks later is turned off.** The shell reaches the pack through a guard: a
  `LinkageError` from it (a missing class or method, a failed static init) turns the pack off,
  answers with the built-in logic and reports it (log, news, `macro_status`). A macro run that hits
  it stops with a clear reason; the next run uses the built-in logic. The broken pack's commit is
  remembered and not loaded again; a newer pack is. Ordinary exceptions of a macro's steps only
  fail that run.
