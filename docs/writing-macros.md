# Writing macros

A macro is a small YAML file that the app runs on the phone. This page covers how the app runs
one, the format, "How it works", inner macros' options and templates. A game app keeps its
macros, their groups and its game notes in its own repository.

## Where a macro lives

- **Library macros** are `macros/<group>/<name>.yaml` in the game app's repository (`group:`
  must match the folder, and be one of game.yaml's `groups:`). The app's CI packs them with their
  templates and data into a `macros-<branch>` pre-release (`tools/macroforge_pack.py --root
  <game repo>`, which first checks every macro), and phones pick up the pack by themselves.
- **Custom macros** are drafts that Claude saves on the phone through the connector
  (`save_macro`). Once one works and fits a group, it moves into `macros/<group>/` with a pull
  request and the phone copy is deleted (`delete_macro`).

The easiest way to make one: in a Claude chat with the phone connector, ask for it ("make a
macro that ..."). Claude looks at the screens, captures templates, writes the YAML, runs it and
fixes it from the stop reason.

## How the app runs a macro

Each tick the runner (`Runner.kt`, steps in `logic/FlowRun.kt`):

1. stops if ■ was pressed or a safety rule fired
2. takes a screenshot
3. finds the **first** screen in `screens:` whose `require` templates are all visible (and none
   of its `reject` ones)
4. runs that screen's `handlers:` actions, or `unknown:` if nothing matched

Handlers do **one small step** and return. The next screenshot decides what happens next, which
makes macros tolerant of missed taps, lag and popups. If the screen stays unrecognised for
`unknown_timeout_s`, the run stops and says so: add a screen for that state, or raise the
timeout.

Positions are **fractions of the screen** (0..1), so a macro works on any resolution. Templates
remember the resolution they were cut from and are rescaled by height (the game runs in
landscape and its UI scales with the short side).

## The format

```yaml
# Collect the shop's free daily gift.
name: shop-gift
description: One line shown in the app.
group: daily
templates: [shop]                     # template folders besides "common" (the user's own are always there)
params:                               # its own options (the inner macros' come along by themselves)
  max_gifts: {default: 3, help: "gifts to collect at most"}
  # option kinds: choices: [...] = pills; type: bool = switch (show_if: <switch> hides an option
  # while that switch is off; beside: <option> draws the switch on the side of that option's box);
  # type: number = number box; anything else = text box
  # global: <key> = the option follows that global parameter (game.yaml globals:, set on
  # System → Settings → Global parameters): it gets a "Global" choice, its default unless it gives
  # another one, and while set to it the run uses the global's value
screens: [shop, home]                 # priority order: specific screens first
define_screens:
  my_screen: {require: [my_template], reject: [other_template]}
unknown_timeout_s: 45
max_visits: {home: 5}               # loop guard: home may come back 5 times; the 6th stops the run as stuck
setup: []                             # actions, once at the start
handlers:
  home:
    - tap: home_shop_button
  shop:
    - tap: shop_gift_button
    - wait: 3
    - run: {macro: watch-ad, until: [shop]}   # its options (watch-ad.*) come along by themselves
    - stop: "the gift: {result}"
unknown: [{wait: 1}]
```

Actions (one key each):

| Action | |
|---|---|
| `tap: name` / `tap: {x: expr, y: expr}` / `tap_exact: {...}` | tap a template where it was found, or a point |
| `drag: {from: [x, y], to: [x, y], ms: expr}` | press, slide, hold |
| `walk: {dir: expr, ms: expr}` | walk (-1 left, 1 right, `up`, `down`) |
| `back: true`, `wait: s`, `launch: package` | |
| `home: true`, `leave_ad: true` | Android Home; leave a commercial that won't close (no X, Back ignored): Home, then the game's own screen, which closes the commercial's activity (never tap inside a commercial) |
| `force_close: true` | the last resort for such a commercial: force-close the game, i.e. swipe the game's own card away in recent apps (found by its name; nothing else there is touched), then Home; sets `closed` |
| `log: "text {expr}"`, `status: "text {expr}"` | write to the log; `status` also shows on the live log line |
| `set: {var: expr}`, `count: stat`, `stop: "reason"` | variables, counters, end the run (`stop: "failed: why"` ends it as failed, like getting stuck: see Routines) |
| `got: "reward text {expr}"` or `got: {item: "Name", n: expr}` | what the macro got (the items a reward text names, "Gold Coin x5"): shown in the outcome message at the end and kept on the Statistics page |
| `if: expr` + `then:` / `else:`, `repeat: {times: expr, do: [...]}` | control flow |
| `run: macro` / `run: {macro, params, until}` | run another macro (basic macros are made for this), sets `result` |
| any step of the game plugin | the game's own steps, written in Kotlin (game.yaml `plugin:`, see [game-profile.md](game-profile.md)) |

Expressions can use the params, variables and `found('t')`, `captured('t')`, `text_has('s')`,
`screen_text(y0, y1)`, `text_after('marker', y0, y1)` (the text after a marker, e.g. a mail's rewards without its title), `game_in_front()`, `ad_in_front()`, `game_screen()` (the game's own
activity in front: no commercial), `on_wifi()` (the phone is on Wi-Fi or another unmetered network), `view_moved()`, `now()`, `pick(i, a, b, ...)`, and three
signs that a commercial has played to its end, so a macro can close it then instead of after a
fixed time (logic/AdSigns.kt):

- `ad_left()`: seconds until the commercial is over by its own signs, 0 = over, -1 = no sign. It
  follows a thin progress bar along the top or bottom of the picture (two flat colours whose split
  moves steadily; also in a portrait commercial, pillar-boxed in the middle) and countdown texts
  ("Reward in 12 seconds", "5 seconds remaining"). Call it about once a pass: it learns the bar's
  speed from the reads.
- `screen_still()`: seconds the picture hasn't changed (the video over, a still end card up).
- `ad_warning()`: a "Close video? You will lose your reward" box is up (closed too early).

All text functions of one pass share one text read of the screen (read again after any step that
may change it, such as `back`, `tap` or `wait`). The full,
current list is the comment at the top of `android/core/src/main/java/macroforge/Flow.kt`.
A new step written in Kotlin goes in `logic/FlowRun.kt` if any game could use it, else in the
game's plugin (see [logic-pack.md](logic-pack.md)).

**Data for a game step.** A macro file may carry more top-level keys than the format above: a
block of data that one of the game plugin's steps reads (a map's layout, a fixed route). The
runner and the pack check ignore keys they don't know. The step reads the block from the macro
it names in its argument, `(ctx.findMacro(name) as? FlowScenario)?.spec?.get("<key>")` (and for
"How it works", `env.file("macros/<group>/<name>.yaml")`). So the data travels with its macro,
one file per macro instead of one shared data file, and a draft saved through the connector runs
with its own data at once. The step names the macro rather than reading "the running macro",
because a macro can also run inside a routine or another macro.

### Routines: macros one after another

A routine has `steps:` instead of screens and handlers. Each step is a macro that runs with its own
saved options (and the routine's own settings for it). A step that fails doesn't stop the
others, unless the routine marks it `required:`: when that one fails (it got stuck, crashed, or
ended with `stop: "failed: ..."`), the steps after it are skipped, except the `always:` ones,
and the outcome message says what was skipped. Use it for a step the rest can't do without,
such as opening the app:

```yaml
name: daily-routine
steps: [open-app, collect-gifts, watch-ads, close-app]
required: [open-app]   # it failed: skip to close-app instead of letting every step time out
always: [close-app]
```

A safety stop or ■ still ends the whole routine at once.

### "How it works": the steps in plain words

The app's Macros page shows, under **How it works**, what a macro does step by step, with the
options as set on that page (change an option and the steps change). Every library macro needs
one of these:

- a routine (`steps:`): nothing to write, its macros are listed and opened up;
- a game plugin step: nothing to write, the plugin describes it (`GamePlugin.explain`);
- anything else: a `how:` list in the macro file.

```yaml
how:
  - "Tap the gift button, at most {max_gifts} times"          # {option} = its value as set
  - {run: watch-ad, text: "Watch the commercial and close it"} # opened up into watch-ad's own steps
  - if: watch_ad                                               # on the options: one branch shows,
    then: ["Watch the bonus ad"]                               # with the options that chose it
    else: ["Skip the bonus ad"]
  - {text: "For each quest:", sub: ["Travel there", "Win it"]} # a step with steps under it
```

Write what the user would see the macro do, in order, not the screen rules (those stay folded
below the steps as "Screen by screen").

### Inner macros' options come along by themselves

A macro takes over the options of every macro it runs, and keeps its own settings for them:
never declare them again. They show in the app under a folded section per inner macro ("Battle
options") and are keyed `<macro>.<option>` (`battle.skill_1`). So every macro that fights has
its own battle settings, and one routine can run a basic macro differently from another.

- **Which macros:** its `run:` steps, a routine's `steps:`, the basic macros a plugin step runs
  (`GamePlugin.stepUses`), anything under a top-level `uses: [...]`, and the macros those run in
  turn. Each inner macro once.
- **Left out:** options every `run:` of it passes in `params:` (the caller decides them), and
  options the caller has itself under the same name (it hands its own down).
- **Unset**, an inner option is the inner macro's own setting; once the outer macro's options
  are saved, it keeps its own copy.
- A Kotlin step that runs a basic macro uses `ctx.runBasic(name)` (options handed down by
  itself) or reads them with `ctx.innerOptions(name)`, and is listed in `stepUses`.
- Own option names never have a dot.

**Reuse first.** Before writing steps into a macro, ask whether that part could be a basic macro
other macros run (`macros/basic/`): its options then reach every macro using it for free.

## Templates and screens

A template is a small crop of the screen (a button label, an icon) that the app looks for with
OpenCV. Through the connector, Claude captures one from the live screen with `save_template` (it
lands in the user's own templates on the phone, usable by name). Library templates ship in the
macro pack as `library.yaml` files with their PNGs:

```yaml
templates:
  home_shop_button:
    notes: The "Shop" button on the home screen (bottom-left).
    file: home_shop_button.png
    ref: [2400, 1080]                  # size of the screenshot it was cut from
    region: [0.0, 0.75, 0.3, 0.25]     # where to search (x, y, w, h)
    threshold: 0.85                    # optional
screens:
  home:
    require: [home_shop_button]
    reject: []
```

An entry with no `ref` or PNG is **not captured yet**, and its screens can't match.

Tips for a good template:

- Cut something that **doesn't change**: button text or an icon, not a portrait, a number or a
  player name.
- Keep it small but distinctive (about 100–250 px wide on a 1080 px tall screen).
- Check that it matches where it should, and **only** there, on several screens.
- Never commit full screenshots or crops that show account data (names, IDs, friend codes), and
  no game art beyond what a template needs.

## Notes on the phone

- **Landscape games:** a pillar-boxed (portrait) capture means another app is in front; no tap
  goes out until the game is back.
- **Every run keeps the screen awake** (`ScreenWake.kt`): a macro keeps it on until it stops,
  and each connector call keeps it on for 10 minutes. An all-black capture means the screen is
  off or capture stopped: taps on it are refused, and a macro stops after 2 minutes of black. A
  PIN or pattern lock can't be passed.
- **Samsung Game Booster's touch lock** dims the game and swallows every tap while templates
  still match through the dim. Its in-game panel and toasts are ignored when the app decides
  which app is in front.
- **Live log:** the overlay line sits bottom right; taps pass through it and `Context.readText`
  ignores what it covers. To read that corner, wrap the read in `LiveLog.without { }`. It shows
  `MacroLog.status`: give every real step one. In a YAML macro use `status: "text {expr}"`; in
  Kotlin call `MacroLog.status(...)` for a step and `MacroLog.show(...)` for a progress counter.
  `screen_text(y0, y1)` returns the OCR text in a band of the screen (`text_after(marker, y0, y1)` only what follows `marker`); `text_row_without(anchor,
  mark, y0, y1)` finds a list row still to do (the top-most line with `anchor` and no `mark`).
  Never put account data in a log line.
