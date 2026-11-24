---
name: capture-templates
description: Capture missing screen templates for a macro-forge game app from the live phone screen through the app's phone connector, verify they match, and add them to the game repo's template library.
---

# Capture templates from the phone

This needs the live phone through the app's phone connector (the app open with screen capture,
taps and the connector on; see `docs/android-app.md`). The game repo's own skill or AGENTS.md
names its connector and setup guide.

1. Find what is missing: entries in a `library.yaml` with no `file`/`ref` yet. The template
   folders are the game.yaml `pack.common` folder and `<pack.folders>/<folder>/templates/`
   (see `docs/game-profile.md`). Read each entry's `notes:`.
2. Get the game onto the screen that shows the element: ask the user, or navigate with the
   connector's `tap`, `swipe`, `walk` and `back`. Take a `screenshot` (its size is in the
   connector's instructions) and look at it. `read_text` gives exact positions of labels.
3. Pick a tight box around a stable element: text or an icon, with no changing numbers,
   player names or animated parts.
4. Try it on the phone first: `save_template` with that box saves it to the user's own
   templates, and `find_template` must find it on this screen and **not** on other screens.
   If it shows up elsewhere, pick a more distinctive element.
5. For the repo (so every user gets it through the macro pack), cut the same box from the
   screenshot saved as a PNG and upscale it to the library's reference size (here 2400 x 1080):

   ```bash
   python -c "import cv2,sys; i=cv2.imread(sys.argv[1]); L,T,W,H=map(int,sys.argv[2].split(',')); s=2400/i.shape[1]; c=i[T:T+H,L:L+W]; cv2.imwrite(sys.argv[3], cv2.resize(c,(round(W*s),round(H*s)),interpolation=cv2.INTER_CUBIC))" shot.png L,T,W,H out.png
   ```

   Put the PNG next to the `library.yaml` and add the entry with `file`, `ref: [2400, 1080]`,
   a `region` a little larger than the box (fractions of the screen), and, because it was
   upscaled from a connector screenshot, `threshold: 0.75` and `scales: [0.9, 1.0, 1.1]`
   (`docs/writing-macros.md`, "Templates and screens").
6. `python <core>/tools/macroforge_pack.py --root . --check` checks the macro pack. After the
   push, the phone picks up the new pack (`update_macros`); run the macro to confirm the
   template matches there.
7. Never commit screenshots of the user's account, only the small template crops.
