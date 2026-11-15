# Aura

An Android app that turns what your watch and phone already measure into goals you can't fake, and a circle of friends to share them with.

- **Any watch.** Steps, exercise, sleep (with stages), heart rate, calories and distance come from Health Connect, so Samsung, Fitbit, Garmin, Pixel Watch and anything else that syncs there works.
- **Verified goals.** Pick a target (8,000 steps, 7 hours of sleep, under 3 hours of screen time...) for a number of days. Aura checks it against the data and awards aura points. Nothing is self-reported.
- **Body and screen.** The day's heart rate laid over the apps you had open, each app's average heart rate, and last night's sleep stages with your phone use around it.
- **Move.** Plan a loop or a route to a dropped pin, record it with GPS, then see the route coloured by the heart rate your watch measured.
- **Circle.** A very light forum: Done posts (from your ledger, goals and activities) and Ask posts (advice, 80 character title, 500 character body), each with one topic, votes and comments. Feed, Trending, Board and Mine. Raw health data never leaves the phone.
- **Trends.** Week bars, a four-week consistency grid, aura over time and this week against last, opened from Today.

Android 12+ only. The UI follows the phone's light or dark mode.

## Earning aura

Four daily earnings, judged from your watch and phone: sleep between 7h and 9h, 8,000 steps (12,000 for more), 30 minutes of exercise, and 3 hours or less of screen time. Each is worth +10 (+15 for 12,000 steps), and all four on one day add +10. Streaks of 3, 7, 14 and 30 days on any one of them add +5, +15, +30 and +60. Two quiet days in a row start to cool your aura, never below the start of your level. Completed goals count too. Levels come from the total: level = floor(sqrt(aura / 50)) + 1.

## Sharing

Anything you earn can be shared as a 1080x1920 image card: a ledger entry, a streak, a completed goal, an activity or your level. The card shows the number, a title, one detail, your name, level and total. It never includes a route, map or location. From the share sheet you can send it to WhatsApp, Instagram or Snapchat (only installed apps are listed), open the system chooser, or save it to Pictures/Aura. Snapchat receives the image only, so it opens its send-to screen rather than a chat message.

Circle is text only. Topics: Sleep, Steps, Training, Screen time, Food, Mind, General. Posts live in `posts/{id}` with votes in `posts/{id}/votes/{uid}` and comments in `posts/{id}/comments/{cid}`; counters are updated in transactions. Trending ranks the last 7 days by `score / (hours + 2)^1.5`.

Aura from Circle (constants in `EarnRules`): each of your posts earns +1 per net upvote up to +20; marking a comment on your Ask as helpful gives its author +10; "Give 5" costs you 5 and gives the author 5 (3 a day, once per post, needs 5 aura). Cross-user awards go through `users/{uid}/gifts/{id}` with fixed ids. Publish the updated `firestore.rules` or Circle shows "Circle needs the updated Firestore rules."

## Building

Needs JDK 17 and the Android SDK (compile SDK 37).

1. Put your Firebase config at `app/google-services.json` (Firebase console > Project settings > Your apps > Aura). It is git-ignored.
2. Create `local.properties` (also git-ignored):

   ```properties
   sdk.dir=/path/to/Android/sdk
   # free key from https://openrouteservice.org, for route planning
   ors.key=...
   ```

3. `./gradlew assembleDebug`

Debug builds have a "Simulate" button on the Record screen that walks a fake route, for testing indoors.

## Releasing

Releases are built and published by GitHub Actions (`.github/workflows/release.yml`).

```bash
git tag v1.0.0
git push origin v1.0.0
```

That builds a signed, minified APK, checks it isn't debug-signed, and publishes it as a GitHub release named `Aura 1.0.0` with `Aura-1.0.0.apk`, its SHA-256 and notes from the commits since the last tag. The version name comes from the tag; the version code is `major*10000 + minor*100 + patch`, so each release installs over the last. You can also run the workflow by hand from the Actions tab with a version.

`ci.yml` builds a debug APK on every push to `main` and every pull request.

### Repository secrets

Settings > Secrets and variables > Actions > New repository secret:

| Secret | What to put in it |
| --- | --- |
| `GOOGLE_SERVICES_JSON` | The whole contents of `google-services.json` |
| `ORS_KEY` | Your OpenRouteService key |
| `AURA_KEYSTORE_BASE64` | The release keystore, base64-encoded (`base64 -w0 aura-release.jks`) |
| `AURA_KEYSTORE_PASSWORD` | Keystore password |
| `AURA_KEY_ALIAS` | Key alias |
| `AURA_KEY_PASSWORD` | Key password |

A release fails early and names any secret that is missing. CI builds without `GOOGLE_SERVICES_JSON` use a stub config, so pull requests from forks still build.

Google sign-in only works for builds whose signing certificate is registered in Firebase: add the release key's SHA-1 under Project settings > Your apps > Aura > Add fingerprint, then update `GOOGLE_SERVICES_JSON`.

## Firestore

`firestore.rules` holds the security rules: profiles and posts are readable by signed-in users and writable only by their owner; goals and follows are private. Paste it into Firebase console > Firestore Database > Rules.

## Privacy

Health readings and app usage are read on the phone and stay there. What reaches Firestore is your name, occupation, aura total, your goals (private to you), and the goal completions and activity totals you choose to post. Routes and locations are never uploaded.
