# J.A.R.V.I.S. — Android

Dynamic app discovery and launching. **No hard-coded app list, and the AI never
decides the final package.**

---

## The trust chain

**There are no rule-based commands.** Every utterance goes to the model; the
model returns a structured intent; the resolver turns that into a real, verified
package. Nothing is matched by regex.

```
User
 ↓  "Open my video editor"
AIEngine.classify  →  IntentResolver
 ↓  Intent: OPEN_APP   target = "video editor"     ← a PHRASE, never a package
AppResolver
 ↓  installed-app inventory (from PackageManager)
 ↓  candidate ranking
 ↓  exact installed package
 ↓  VERIFY the package really exists
ActionRegistry → AppLauncher
 ↓  Android OS
```

**Cost of dropping the rules, stated plainly:** an API key is now required for
every request, and each one costs at least one model round-trip. In exchange the
assistant understands phrasing nobody wrote a rule for.

The rule this enforces:

- The **AI produces an intent + a target phrase**. It is never asked for, and
  never trusted with, a package name.
- The **AppResolver is the only source of package names** — they come from the
  device's real inventory.
- When the local ranking is ambiguous, the AI may only **choose a number** from
  the candidate list the resolver already produced (`AIEngine.chooseCandidate`).
  It cannot invent a name, because it only ever returns an index.
- **`AppResolver.verify()` re-checks** the package is installed and launchable
  immediately before launch, and `AppLauncher.launch()` is a second gate: no
  launch intent, no `startActivity`.

---

## Modules

| Module | File | Responsibility |
|---|---|---|
| **VoiceEngine** | `voice/VoiceEngine.kt` | speech ↔ text; speed / voice / language from Settings |
| **AIEngine** | `ai/AIEngine.kt` | conversation, intent classification, candidate *choice by index* |
| **IntentResolver** | `nlp/IntentResolver.kt` | maps the model's intent onto a device command |
| **DeviceCommand** | `nlp/DeviceCommand.kt` | the command types (now AI-produced) |
| **AppResolver** | `apps/AppResolver.kt` | **discovers, ranks and verifies installed apps** |
| **ActionRegistry** | `actions/ActionRegistry.kt` | *how* to act on a resolved app (launch today; deep links next) |
| **AppLauncher** | `apps/AppLauncher.kt` | final gate + store fallback |
| **DeviceTools** | `device/DeviceTools.kt` | time, date, arithmetic (no key needed) |
| **Memory** | `memory/Memory.kt` | settings + learned aliases |
| **SecureStore** | `security/SecureStore.kt` | **Keystore-encrypted credential storage** |
| **UI** | `ui/MainActivity.kt` | wiring only |
| **Settings** | `ui/SettingsActivity.kt` | AI · Voice · Resolver · Permissions · About |
| **JarvisCore** | `core/JarvisCore.kt` | the shared pipeline — used by the UI *and* the service |
| **JarvisService** | `service/JarvisService.kt` | foreground mic service, wake word, listening loop |
| **BootReceiver** | `service/BootReceiver.kt` | tap-to-resume notification after a reboot |
| **Caller** | `device/Caller.kt` | place a call from a number or a contact name |
| **WakeWord** | `nlp/WakeWord.kt` | wake-word matching |

Adding a capability means adding a module; the AI core and the UI don't change.

---

## How AppResolver works

1. **Discovery** — `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)` builds
   the inventory from the device at runtime.
2. **Local scoring** — label / prefix / substring / package tokens, plus a
   **concept table** describing *kinds* of apps (`"coding" → termux, code, dev,
   ide, git, acode, replit…`), so "the app I use for coding" matches whatever is
   actually installed. The table never names a product as a hard-coded target.
3. **AI tie-break (optional)** — when the top two scores are within 8 points or
   the best is below the confidence threshold, the shortlist goes to the model,
   which returns a **number**. That is the semantic step, and it is boxed in.
4. **Verify + memory** — the chosen package is re-verified, then the phrase →
   package mapping is remembered so next time is instant.

Nothing matching → a Play Store search, never a dead end.

---

## Credential storage

The API key is **not** written to plain SharedPreferences. `SecureStore` encrypts
it with AES/GCM using a key that lives in the **Android Keystore** and never
leaves it; only the ciphertext and IV reach SharedPreferences. `Memory` also
**migrates any key left by an older plaintext build** into encrypted storage and
deletes the plaintext copy.

---

## Settings

```
JARVIS Settings
├── AI Provider      provider preset · API key · model · base URL
├── Voice            speech speed · TTS voice · recognition language · test
├── App Resolver     refresh index · confidence threshold · learned aliases (deletable)
├── Permissions      microphone status + grant
└── About
```

Open it with the **Settings** button on the main screen.

---

## 24/7 background listening — what Android actually allows

The OS, not this code, draws the line here. What is and isn't possible:

| Wanted | Reality |
|---|---|
| Keep listening after you swipe the app away | **Yes** — a foreground service with `foregroundServiceType="microphone"` plus a persistent notification. This is the only supported mechanism. |
| Keep listening with the notification hidden | **No** — the microphone is tied to the foreground service. Hide it and the mic is revoked. |
| Auto-start listening at boot | **No** — Android 12+ forbids starting a microphone service from the background. `BootReceiver` posts a *tap-to-resume* notification instead of silently starting. |
| Survive aggressive battery managers | **Partly** — the app requests exemption from battery optimisation, but some OEMs (Xiaomi, Oppo, Vivo, Samsung) also need the app whitelisted in their own settings. |
| Use the mic from the background without a service | **No** — Android 11+ blocks this outright. |

So **closing JARVIS from Recents does not stop it** — the foreground service keeps
running with its notification. A reboot or a force-stop does, and that is by design.

**Wake-word model.** The system recogniser is not a wake-word engine, so the
service runs short recognition sessions in a loop and only acts when the
transcript contains "Jarvis". That keeps false positives and battery drain down.
A dedicated on-device wake-word model (e.g. Picovoice Porcupine) is the upgrade
path if you want true "say it from silence".

## Calling

Say **"call 9876543210"** or **"call mom"**.

- With `CALL_PHONE` granted, it rings directly (`ACTION_CALL`).
- Without it, it opens the dialer prefilled (`ACTION_DIAL`).
- A spoken *name* is resolved through your contacts (`READ_CONTACTS`).
- Android 10+ blocks starting an activity from the background, so a call asked
  for by the background service may be refused — JARVIS says so rather than
  pretending it worked.

---

## The Android package-visibility catch

Since Android 11 an app can only see apps it declared an interest in. This
project uses the narrow, Play-Store-friendly approach:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

The blunt alternative, `QUERY_ALL_PACKAGES`, is documented in
`AndroidManifest.xml` — Google restricts it to specific app categories.

---

## Launching ≠ controlling

- **Launching** an installed app: `getLaunchIntentForPackage` → `startActivity`.
- **Acting inside** another app needs that app to expose a deep link, an intent,
  or an accessibility service. You cannot drive arbitrary apps.

That is exactly what `ActionRegistry` is for. Today it implements `Launch` only;
`DeepLink` and `ExplicitIntent` are declared and waiting. Adding deep links is a
**data change** — a `package → Action.DeepLink(uri)` entry in its `overrides`
map — with no edits to AppResolver or the UI.

---

## Install

A browser page cannot enumerate installed apps — that needs the OS. So the
build that gives **every** feature is the APK, which ships with this project as
`jarvis.apk`.

Copy it to your phone and open it. You'll be asked to allow installing from
unknown sources — that's expected for any app not from the Play Store. It is
debug-signed, so it installs directly with no developer account.

The web page (https://pj9811193-create.github.io/jarvis/) still works for
conversation and the offline skills, but it *cannot* open apps.

---

## Build & run

1. Open `jarvis-android` in **Android Studio** (Giraffe or newer), let it sync.
2. Run on a device or emulator — **Android 7.0 (API 24)** or newer.
3. Grant the microphone permission when asked.

```bash
cd jarvis-android
gradle wrapper          # once, to generate ./gradlew (wrapper jar is binary, not committed)
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Enable the AI in **Settings → AI Provider** (Groq and Google Gemini both have
free tiers). Without a key, discovery, launching, time, date and arithmetic all
still work.

---

## Things to try

- "Open my video editor" → InShot / CapCut / whatever you have
- "Open the app I use for coding" → Termux / Acode / Replit
- "Open WhatsApp" · "Install some game" · "What's 47 times 19"

---

## Feature coverage against the 100-item list

### Working in this build

**Core AI** — natural-language conversation · context-aware answers (rolling history)
· short-term memory · personal preferences (system prompt) · multiple providers ·
cloud AI · offline device skills with no key.

**Voice** — "Jarvis" wake word · push-to-talk · multilingual recognition (set the
language in Settings) · natural TTS · voice-speed control.

**Phone control** — open apps · volume up/down/max/mute · flashlight on/off ·
brightness up/down/max · battery level · Wi-Fi & Bluetooth panels.

**Communication** — make calls (number *or* contact name) · find contacts ·
SMS draft · WhatsApp draft · email draft · read notifications · summarise
notifications with the AI.

**Productivity** — timers · alarms · calendar events · notes · to-do list ·
daily briefing (time, date, battery, notifications, tasks).

**Internet** — web search · weather (no API key needed) · maps/search ·
fact-checking and summaries via the AI.

**Automation & access** — home-screen widget · Quick Settings tile ·
lock-screen quick launch (widget) · Mission Control dashboard · ActionRegistry
skill seam.

### Deferred — needs more work, not blocked

Camera vision (objects, text, QR, plants) needs CameraX + ML Kit. Screen
intelligence / OCR / "read what's on screen" needs MediaProjection plus an
Accessibility service. Streaming responses need SSE parsing. Local/offline AI
needs a bundled model (e.g. MediaPipe LLM). Also not yet done: automatic model
selection, voice authentication, noise filtering, recurring reminders, voice
notes, multi-step command chaining, scheduled automations, routine learning,
personal knowledge graph, agent mode, MCP, PC control, smart home, AR.

### Not possible on Android — blocked by the OS, not by this code

| Feature | Why |
|---|---|
| Toggle Wi-Fi / Bluetooth directly | Android 10+ removed this; apps may only open the panel. |
| Close or force-stop other apps | No public API; only a device owner / rooted build could. |
| Take a screenshot silently | Needs an Accessibility service or MediaProjection consent. |
| Lock the screen | Needs Device Admin enrolment. |
| Always-on mic without a foreground service | Android 11+ blocks background mic outright. |
| Auto-start listening after reboot | Android 12+ forbids a microphone service starting from the background. |
| Read notifications | Only with user-granted notification access (supported, and wired up). |

---

## Build status

Built and verified with a real Android toolchain:

```
BUILD SUCCESSFUL
app/build/outputs/apk/debug/app-debug.apk   5.5 MB
package ai.jarvis · minSdk 24 · targetSdk 34 · compileSdk 34
permissions: RECORD_AUDIO, INTERNET, CALL_PHONE, READ_CONTACTS,
             FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE,
             POST_NOTIFICATIONS, WAKE_LOCK, RECEIVE_BOOT_COMPLETED,
             REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
             (no QUERY_ALL_PACKAGES)
service: ai.jarvis.service.JarvisService  foregroundServiceType=0x80 (microphone)
receiver: ai.jarvis.service.BootReceiver  BOOT_COMPLETED
signed with the Android debug certificate
```

Toolchain: JDK 17 (Temurin) · Gradle 8.7 · AGP 8.5.2 · Android SDK platform 34
+ build-tools 34.0.0.

`gradle.properties` is tuned for a small build box (1 GB heap, in-process
Kotlin compiler, no daemon). On a normal machine, raise `org.gradle.jvmargs`
for much faster builds.
