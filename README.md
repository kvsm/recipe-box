# Recipe Box

An Android app that keeps every recipe you find in one tin. Share a recipe page, an Instagram post or a cookbook screenshot to it, and it saves a clean copy in the standard schema.org Recipe format.

## Getting it running

1. Open the `recipe-box` folder in Android Studio (Ladybug or newer) and let Gradle sync.
2. Run the `app` configuration on a phone or emulator (Android 8.0+).
3. The first build downloads the two typefaces (Bricolage Grotesque and Atkinson Hyperlegible, both open-licence) into `app/src/main/assets/fonts/`. If that fails, the app still runs with system fonts.

Dependency versions in `gradle/libs.versions.toml` are a known-good set from late 2024. Android Studio will offer upgrades; take them.

## What it does

- **Share to save.** "Save to Recipe Box" appears in the share sheet for links, text and images. Imports run in the background (WorkManager), so you can go straight back to what you were doing.
- **Import pipeline.** Recipe sites almost all embed schema.org Recipe JSON-LD, which is parsed for free. Pages without it, Instagram captions, pasted text and screenshots go to Claude (your own API key) with a forced tool call, so the output is always structured.
- **Get ready (mise en place).** Every import gets a prep pass. Prep hiding in ingredient notes ("onions, finely chopped") or later steps ("preheat the oven", "marinate overnight") moves into a "Get ready" list at the start. Anything needing a long lead time gets a "Start the night before" sticker. With a Claude key the whole method is restructured; without one, a built-in pass catches the obvious cases. The imported method is always kept, behind "Show original".
- **Ingredient reading.** With a key, the same Claude call that does the prep pass also reads every ingredient line into name, amount, unit and note. Without one, the built-in parser handles the common cases.
- **Fix a line by hand.** Hold any ingredient on a recipe card to correct its item, amount, unit or note, with the recipe's original line pinned above and a live preview. Reset puts it back to how the recipe had it. Hand fixes count as parser lessons too, marked `"source": "you"`.
- **Lessons for no-key mode.** Every time Claude is used, the matching built-in fallback runs too, and any disagreement is saved on the phone (`LessonLog`, `lessons.jsonl`) with the input and both answers. It covers ingredient lines, captions and page text, screenshots (on-device text vs Claude's reading), Get ready, and shopping categories. Your own hand fixes and your OurGroceries filing count too, marked `"source": "you"`, and they're never overwritten by Claude's. Capped at 500 per area. Settings → Lessons for no-key mode shows the counts and exports the file. See `docs/lessons.md` for turning an export into a release.
- **Nothing is lost.** The untouched source (JSON-LD, page text, caption or screenshot) is stored in `raw_sources`, so recipes can be re-processed when the parser improves.
- **Shopping list.** Tick what you've got, then "Add N to list" sends the rest to OurGroceries (see below) or copies it to the clipboard.
- **Cook mode.** Plum night mode, big type, one step per page, ingredients highlighted, timers pulled out of the step text, screen kept awake.
- **Recipe card.** Only the ingredient list scrolls; the photo, title, servings and Add to list stay put. On phones the method is on a Method tab beside Ingredients.
- **Tablets and foldables.** At 840dp and wider (tablet landscape, big foldables open), the tin and the open recipe sit side by side, with the open card straightened up and highlighted. Any recipe pane 700dp or wider splits into ingredients on the left and prep and method on the right. Cook mode on a wide screen keeps a tick-off ingredient list beside the steps, scaled to the servings you picked. The tin grid adds columns as space allows, and Settings stays in a readable column.
- **Photos.** Each recipe's photo is downloaded and kept on the phone at import, because image links (Instagram's especially) expire. The app tries the recipe's own image, then YouTube's thumbnail for YouTube links, then the page's share image, which for Instagram, TikTok and Facebook is the post photo or the video's cover frame. A screenshot import only becomes the card photo if Claude says it shows the food. Tap the camera button on a recipe card to take your own photo, pick one from your gallery, or remove it.
- **Heating up.** Oven, grill, air fryer, barbecue and pan-of-water instructions always go in Get ready, near the top. If one isn't needed until later in the recipe, Get ready says "Not till step N" and the instruction stays in the method at the right point too. When Claude does the prep pass, a built-in check adds back any heat-up it missed.
- **Metric or imperial.** Settings → Measurements (metric by default). Amounts are scaled first, then converted and rounded to sensible kitchen numbers; recipes stay stored as imported, so switching is lossless. In metric, cups of dry ingredients (flour, sugar, oats…) become grams. Teaspoons, tablespoons and counts are never converted. Pints, fluid ounces and cups follow where the recipe came from: UK and Irish sites use British pints; UK, Irish, Australian and NZ sites use 250 ml cups. Oven temperatures in the steps are converted too, with the original kept in brackets.
- **Removing recipes.** Long-press a card in the tin, or use "Remove from the tin" at the end of a recipe's method. Either way there's an Undo for a few seconds afterwards; the recipe comes back with its photo, edits and saved original intact.
- **Export.** Settings → Export writes every recipe as schema.org JSON.

## Without a Claude key

Claude makes imports better, never possible. Everything has a path without it:

| | With a key | Without (or if Claude fails) |
|---|---|---|
| Recipe sites | The page's own recipe data | Same, plus microdata and the big WordPress recipe plugins |
| Other pages, captions, pasted text | Claude reads it | Built-in reader: splits on Ingredients/Method headings, or spots ingredient-looking lines |
| Screenshots | Claude reads the image | On-device text recognition (ML Kit, offline), then the built-in reader |
| Ingredient amounts | Claude | Built-in parser |
| Get ready / heat-ups | Claude restructure | Built-in prep pass |
| Shopping categories | Your history, then Claude | Your history, then keywords |

Temporary Claude problems (network, busy) retry first; on the last try the import falls back to the built-in reader rather than failing. Recipes read without Claude carry a "Read without Claude" sticker, and once a key is added, "Tidy up with Claude" re-reads them from the saved original.

**Rule for new features:** anything Claude-powered ships with a built-in fallback that's cautious rather than clever, and logs its disagreements with that fallback to `LessonLog` under its own area. For the planned waiting/timeline feature that means: without a key, show the "Heads up" warning from cue words ("meanwhile", "while it simmers") and explicit mid-wait reminders ("10 minutes before the end"), but don't reorder or back-time anything.

## OurGroceries

The app uses the API behind the OurGroceries website, as OurGroceries support recommended: their MCP server's tools can change at any time, which agents cope with and a compiled app doesn't. The client is a Kotlin port of the MIT-licensed [py-our-groceries](https://github.com/ljmerza/py-our-groceries) (the library Home Assistant uses). It's unofficial, so it may need fixing if OurGroceries changes its site.

- **Sign-in:** email and password in Settings. There's no OAuth, so both are stored encrypted (Tink + Android Keystore) to sign in again when the session cookie expires, and only ever sent to ourgroceries.com.
- **Adding items:** `getOverview` finds the list by name (default "Shopping"). The account's category list and every list's items, including crossed-off history, give the categories you actually use. Then one `insertItems` call adds everything, each with a `categoryId` where one matched and the scaled quantity as its note. Items with no match are left for OurGroceries to file itself.
- **Fallback:** if you're not signed in or a call fails, items are copied to the clipboard.

## Code map

```
app/src/main/java/app/recipebox/
  RecipeBoxApp.kt          app class + dependency wiring
  data/                    Room database, entities, repository, models
  importer/                JSON-LD + schema.org mapping, ingredient parser,
                           Claude client/extractor, prep planner, import worker
  groceries/               OurGroceries MCP client, OAuth redirect, clipboard fallback
  share/                   share-sheet target
  export/                  schema.org JSON export
  settings/                encrypted secret store (Tink + Android Keystore)
  ui/                      Compose screens, theme and sticker components
```

Unit tests for the parser, mapper and prep pass are in `app/src/test`. Run them with `./gradlew test`.

## Known gaps

- Cook-mode timers live on the screen; leaving cook mode cancels them. Move them to a foreground service with notifications.
- Only ingredients can be edited in-app so far; titles, steps and prep can't yet.
- Instagram pages can be login-walled. When a link fails, share a screenshot or the caption text instead.
- No launcher icon yet.
