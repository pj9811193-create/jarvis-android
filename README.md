# J.A.R.V.I.S. — Android

Dynamic app discovery and launching. **No hard-coded app list.**

```
"Open my video editor"
        ↓
   VoiceEngine        speech → text
        ↓
   IntentParser       is this a device action?
        ↓
   AppResolver        ← THE CORE: discovers what's installed, matches semantically
        ↓
   AIEngine           (optional) picks the best candidate when scores are close
        ↓
   AppLauncher        launches by package via the device's own launch intent
```

---

## Why this is a native app, not the web page

The earlier version was a single HTML file. **A web page cannot enumerate the
apps installed on an Android device.** No browser API exposes the package list —
`navigator.getInstalledRelatedApps()` only returns apps that the *same developer*
has registered, which is useless for this. Any "installed apps" feature must run
in a native Android app with access to `PackageManager`.

That is the whole reason this module exists as an Android project.

---

## The modules

| Module | File | Responsibility |
|---|---|---|
| **VoiceEngine** | `voice/VoiceEngine.kt` | Speech → text, text → speech (system services only) |
| **AIEngine** | `ai/AIEngine.kt` | Any OpenAI-compatible endpoint; also the app tie-breaker |
| **IntentParser** | `nlp/IntentParser.kt` | Offline, deterministic detection of device verbs |
| **AppResolver** | `apps/AppResolver.kt` | **Discovers + scores installed apps** |
| **AppLauncher** | `apps/AppLauncher.kt` | Launches a package, or falls back to its store page |
| **DeviceTools** | `device/DeviceTools.kt` | Time, date, arithmetic (work with no AI key) |
| **Memory** | `memory/Memory.kt` | AI settings + learned phrase→package aliases |
| **UI** | `ui/MainActivity.kt` | Wires it together; holds almost no logic |

Adding a new capability means adding a module — the AI's core logic and the UI
never change.

---

## How AppResolver actually works

1. **Discovery.** It asks the `PackageManager` for every activity that answers
   `ACTION_MAIN` + `CATEGORY_LAUNCHER` — i.e. every app with a launcher icon.
   The inventory is built at runtime from the device, and re-scanned on demand.

2. **Local scoring (offline, instant).** Each installed app is scored against the
   user's words: exact label, prefix, substring, token hits on label and package
   id, and matches against a **concept table**. The concept table describes
   *kinds* of apps — `"coding" → termux, code, dev, ide, studio, git, acode,
   replit …` — so "the app I use for coding" matches whatever is actually
   installed. It never names a specific product as a hard-coded target.

3. **AI tie-break (optional).** If the top two scores are within 8 points, or the
   best score is weak, the shortlist is sent to the model, which replies with the
   single best-matching app name. That is the "semantic matching" step — the AI
   reasons over the *user's own* inventory.

4. **Memory.** The resolved phrase → package mapping is stored, so saying
   "open my video editor" a second time is instant and needs no AI call.

If nothing matches, it opens a **Play Store search** rather than failing.

---

## The Android package-visibility catch (important)

Since Android 11 (API 30), an app can only see other apps it has declared an
interest in. Without that declaration, `queryIntentActivities` returns almost
nothing. There are two ways to get visibility, and this project uses the first:

**1. `<queries>` declaration (used here — Play-Store friendly).**

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

This makes every app with a launcher activity visible — exactly the set we need.

**2. `QUERY_ALL_PACKAGES` (blunt, restricted).**

```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```

This sees everything, but Google restricts it to specific app categories
(launchers, antivirus, file managers, accessibility tools, …) and rejects
unrelated apps that request it. Prefer `<queries>` unless you genuinely qualify.

---

## Launching ≠ controlling

Worth being explicit, because it bounds what this can do:

- **Launching an installed app** — straightforward, and that's what `AppLauncher`
  does: `getLaunchIntentForPackage(pkg)`, then `startActivity`.
- **Doing something *inside* another app** — needs that app to cooperate:
  a documented `Intent`/deep link (e.g. `whatsapp://send?text=…`), a content
  provider, or an accessibility service. You cannot drive arbitrary apps.
  When you need an in-app action, add a deep-link map for the apps that expose
  one and fall back to launching the app otherwise.

---

## Build & run

1. Open the `jarvis-android` folder in **Android Studio** (Giraffe or newer).
2. Let it sync (Gradle will fetch the AndroidX/Material dependencies).
3. Run on a device or emulator — **Android 7.0 (API 24)** or newer.
4. Grant the microphone permission when asked.

From the command line, if you have a Gradle wrapper or Gradle installed:

```bash
cd jarvis-android
gradle wrapper        # once, to generate ./gradlew
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

> The project ships without a `gradle-wrapper.jar` (binary). Android Studio
> generates it on first sync, or run `gradle wrapper` as above.

### Enabling the AI

Long-press the **J.A.R.V.I.S.** title to open settings and enter:

| Field | Example |
|---|---|
| API key | your Groq / Gemini / OpenAI key |
| Base URL | `https://api.groq.com/openai/v1` |
| Model | `llama-3.3-70b-versatile` |

Without a key, discovery, launching, time, date and arithmetic all still work —
only open-ended conversation and the semantic tie-break need the AI.

---

## Things to try

- "Open my video editor" → matches InShot / CapCut / whatever you have
- "Open the app I use for coding" → Termux / Acode / Replit
- "Open WhatsApp"
- "Install some game" → Play Store search
- "What's 47 times 19"
- "Search for the best laptops"

---

## Not compiled here

This source was written but **not compiled** in the environment that produced it
(no Android SDK / Kotlin compiler available). It is structured to build as-is in
Android Studio; if your Gradle/AGP versions differ, update them in
`build.gradle.kts` / `app/build.gradle.kts`.
