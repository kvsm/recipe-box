# Turning lessons into a release

The app saves a lesson each time Claude (or you) gets a better answer than one of the built-in fallbacks. Every so often, fold the common ones back into the fallbacks so the no-key mode improves.

## 1. Export

On the phone: Settings → Lessons for no-key mode → Export lessons. You get `recipe-box-lessons.jsonl`, one JSON object per line:

```json
{"area":"ingredient","key":"2 x 400g tins plum tomatoes","source":"claude","at":1791790000000,
 "input":"2 x 400g tins plum tomatoes",
 "built_in":{"name":"x 400g tins plum tomatoes","quantity":2.0},
 "claude":{"name":"plum tomatoes","quantity":2.0,"unit":"x 400g tin"}}
```

| area | built-in fallback | file to improve |
|---|---|---|
| `ingredient` | ingredient line parser | `importer/IngredientParser.kt` |
| `reader` | caption / text / page reader | `importer/BuiltInReader.kt` |
| `screenshot` | on-device text + reader | `importer/BuiltInReader.kt` (OCR quirks) |
| `prep` | Get ready / heat-ups | `importer/PrepPlanner.kt` (`HeuristicPrep`) |
| `category` | shopping category keywords | `groceries/CategoryMatcher.kt` (`KeywordCategories`) |
| `counts` | words the method counts ingredients by ("yolks" for eggs) | `data/model/Units.kt` (`countAliases`) |

`"source": "you"` means you fixed or filed it yourself. Treat those as ground truth.

## 2. Hand it to Claude

Start a chat with the repo and the export attached, and paste:

> Here's an export of Recipe Box lessons. For each area, group the entries into patterns the built-in fallback gets wrong. Rank patterns by how many entries they explain, with entries marked "you" counting double. For the top patterns, change the matching file in the table in docs/lessons.md so the built-in answer matches the better one. Add a unit test per pattern, using real lines from the export. Don't chase one-offs: a pattern needs at least 3 entries, or 1 from "you". List what you changed and which patterns you left alone.

## 3. Check and ship

- Run `./gradlew test`. The new tests should pass and no old ones should break.
- Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
- Clear the lessons on the phone after updating, so the next export only holds what's still being missed.

## Privacy

Lessons hold recipe text (captions, page text, text read from screenshots), ingredient lines, method steps, shopping items and your category names. Nothing is sent anywhere unless you export it. Screenshot text can include whatever else was on screen, so skim the `screenshot` entries before sharing an export with anyone.
