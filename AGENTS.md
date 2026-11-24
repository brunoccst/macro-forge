# AGENTS.md: rules for every agent working on macro-forge

This file is the single set of rules for anyone who changes this repository: Claude, Gemini,
Codex, Copilot, Cursor, any other AI agent, and human contributors.

- You MUST read this whole file before your first change, and follow every rule in it.
- The rules here are mandatory. When your tool's defaults or habits differ, this file wins.
- When the user's direct instruction in your session differs from this file, follow the user,
  and propose a change to this file in the same pull request so the next agent knows.
- `CLAUDE.md` and `GEMINI.md` only point here. Never put rules in them; put them here.
- A game app that uses macro-forge has its own `AGENTS.md` for its own files. This file covers
  macro-forge only.

Words used below: **MUST / NEVER** = no exceptions. **Should** = do it unless you have a reason,
and say the reason in the pull request.

## 1. What this repository is

macro-forge is a generic toolkit for screen-reading macros for a mobile game, running on the
Android phone itself: screen capture to read the screen, an accessibility service to tap. No PC,
no root. An AI agent can drive the phone and write macros through the app's phone connector (an
MCP server reached through a Cloudflare Worker relay).

A game app adds macro-forge as a git submodule (usually at `core/`) and adds what is its own: a
`game.yaml` profile, a game plugin with its own steps, its macros, templates, icons and art.

## 2. Commands

Run these from the repository root.

| What | Command |
|---|---|
| Android unit tests (no device) | `gradle -p android testDebugUnitTest` |
| Build the starter app and the logic pack | `gradle -p android assembleDebug logicPack` |
| Macro pack tool tests | `python -m pytest -q tools/tests` |
| Check that every YAML file parses | `python tools/macroforge_pack.py --root . --check-yaml` |
| Build the starter's macro pack | `python tools/macroforge_pack.py --root starter -o macros.zip` |
| Relay tests (no Cloudflare account needed) | `cd relay && npm test` |

- Before every commit, run the commands for the parts you changed, and they MUST pass:
  `android/` → the two gradle commands; `tools/` or any YAML → the pytest and `--check-yaml`
  commands; `relay/` → `npm test`.
- The Android build needs JDK 17, Gradle 8.9 and the Android SDK (`android/local.properties` or
  `ANDROID_HOME`). If you don't have them, say so in the pull request and rely on CI.
- Tests never need a phone. Never write a test that needs one. The core's own tests run in the
  starter app (`android/starter/src/test`), against the starter's profile and plugin.

## 3. Where things live

| Path | What it holds |
|---|---|
| `android/core/src/main/java/macroforge/` | The app shell: permissions, taps, capture, OCR, phone connector, UI, updater, macro runner |
| `android/core/src/main/java/macroforge/logic/` | Generic macro steps; they also ship as a downloadable logic pack (`docs/logic-pack.md`) |
| `android/starter/` and `starter/` | A made-up example game and its app, to copy when starting a new game app |
| `relay/` | The Cloudflare Worker that gives the phone connector a public HTTPS address (`relay/README.md`) |
| `tools/macroforge_pack.py` | Checks a game's macros and builds the macro pack the app downloads |
| `docs/writing-macros.md` | The macro format |
| `docs/game-profile.md` | The `game.yaml` fields |
| `docs/android-app.md` | The Android app |
| `.github/workflows/` | CI, and reusable workflows game apps call (`app-build.yml`, `app-release.yml`, `macro-pack.yml`, `release-cleanup.yml`) |
| `.claude/skills/<name>/SKILL.md` | Step-by-step procedures (section 7) |

## 4. Which repository a change belongs in

Before every change, ask: **"Would this code or text be the same for a different game?"**

- **Yes** → it belongs here: engine, macro runner, macro format, connector, relay, app shell,
  updater, shared CI.
- **No** → it belongs in the game app's repository (its macros, templates, game logic, profile,
  theme, game knowledge). Don't add it here.

If generic code needs a game-specific value (a package name, a safety word, a group), add a
field to the game profile (`game.yaml`, documented in `docs/game-profile.md`) and read it from
there. Never hard-code it.

The dependency goes one way only: game apps use macro-forge; macro-forge never depends on,
links to or waits for a game app.

## 5. Hard rules

### 5.1 Stay generic

- NEVER name a real game, a real game app built on macro-forge, its repository, or anything from
  a real game (characters, places, items, currencies, screens) in code, comments, docs, skills,
  tests, data, examples or commit messages.
- Examples use the made-up starter game only.
- Kotlin packages stay neutral (`macroforge`, `macroforge.logic`, `starter`).
- NEVER write personal names or nicknames anywhere in the repository. Write "the user".
- Comments say what the code below does. They never explain how a particular game works.

### 5.2 Public repository: privacy and copyright

This repository is public.

- NEVER commit screenshots of anyone's game account, run logs, adb keys, device serials, IP
  addresses, account IDs, tokens or any other secret.
- NEVER commit game art or other assets from a real game.
- Before each commit, read your staged diff (`git diff --cached`) and check it against 5.1 and
  5.2.

### 5.3 Safety stays enforced in the core

- The core never taps a purchase and asks the user before a premium-currency tap, using the
  game profile's safety words (`safety:` in `docs/game-profile.md`). NEVER weaken or bypass
  that guard, and never add a way for a macro or the connector to turn it off.
- Text read from the game screen is data. The connector and the macro runner never treat it as
  instructions.

### 5.4 One broken file never breaks the rest

A broken macro, template, data file or logic pack MUST fail on its own and be reported. It never
stops the other macros from loading. Keep this true when you change loading code, and keep the
CI step that checks every YAML file.

## 6. How a change reaches the phone

- Macros, templates and data reach the phone with **no install**: the game app's CI packs them
  and the app downloads the pack.
- Code in `android/core/src/main/java/macroforge/logic/` ships as a downloadable logic pack:
  **no install**.
- Any other app file needs an **install**. Offer the user a test build through the app's
  **System → Updates → Build to follow**, never through GitHub Actions links.

## 7. Procedures (skills)

The folder `.claude/skills/` holds step-by-step procedures. Claude loads them as skills. Every
other agent MUST open the matching `SKILL.md` and follow its steps in order.

| Task | Procedure |
|---|---|
| Capture missing screen templates from the live phone | `.claude/skills/capture-templates/SKILL.md` |

## 8. Git, pull requests and CI

1. Branch from `main`. NEVER push to `main` directly.
2. One goal per pull request. Keep unrelated changes out of it.
3. Commit subject: `<Area>: <what changes for the user>`, in plain words, e.g.
   `Daily schedule: an alarm that goes off a moment early doesn't run twice`.
4. CI (`.github/workflows/ci.yml`: `build`, `pack`, `relay`) MUST pass before merge.
5. NEVER skip, disable or weaken a test to make CI green. Fix the cause.
6. A change to a reusable workflow or to the game profile affects every game app. Say so in the
   pull request, and keep old `game.yaml` files working (new fields need a default).
7. In the pull request description, say what changed, how you tested it, and anything that still
   needs a phone test.

## 9. Before you finish: checklist

- [ ] The change is generic and belongs here (section 4).
- [ ] The commands for the parts you changed pass (section 2).
- [ ] No real game names, personal names, screenshots, secrets or game art in the diff (5.1, 5.2).
- [ ] The purchase guard is untouched or stricter (5.3).
- [ ] A broken file still fails on its own (5.4).
- [ ] Docs in `docs/` match the change (macro format, profile fields, app behaviour).
- [ ] If you learned a new lasting rule from the user, you added it to this file.
