# Maatram — Android app (Capacitor)

appId `com.maatram.app` · display name **Maatram**

## Build it

1. **Android Studio** → *Open* → select the **`android/`** folder (not the parent)
2. Wait for Gradle sync
3. Phone with USB debugging on → press **Run** ▶

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

Node is **not** required. `node_modules/@capacitor/*` is included because
Gradle reads the plugin modules from there — see `android/capacitor.settings.gradle`.
Deleting or moving that folder breaks the build with
`No matching configuration of project :capacitor-android was found`.

## Folder layout — keep it intact

```
maatram-android/
├── android/                 <- open THIS in Android Studio
├── node_modules/@capacitor/ <- required by Gradle, do not delete
├── www/                     <- the 8 pages + native.js, edit here
├── capacitor.config.json
└── firestore.rules          deploy in the Firebase console
```

`android/` and `node_modules/` must stay siblings.

## After editing any HTML

With Node installed:
```bash
npm install
npx cap sync android
```

Without Node, copy the changed files by hand into
`android/app/src/main/assets/public/`.

## What the native layer adds

| Piece | Effect |
|---|---|
| `MainActivity.java` | edge-to-edge, draws behind status bar + gesture bar, notch-aware |
| `native.js` | hardware back button, splash dismiss, status-bar style, native haptics, keyboard hides the tab bar |
| Manifest | portrait lock, `adjustResize`, INTERNET / VIBRATE / WAKE_LOCK |
| Icons + splash | gradient **M** mark, all densities |

Back button: closes an open bottom sheet → closes the power-up → exits the
feature menu → goes back a page → exits the app on Home.

## Before Google sign-in works

Firebase console → project `maatram-859f4` → Project settings → Add app → Android
* package name `com.maatram.app`
* SHA-1 from:
```bash
cd android
gradlew signingReport
```
Copy the SHA1 under `Variant: debug`. Add the release SHA-1 later when you sign.

## Internet

Motion, Firebase and Google Fonts still load from CDNs. Offline the pages still
open and every timer, lock and counter works — animations render static.

## Not built yet

Real app blocking needs a Kotlin plugin (`UsageStatsManager`,
`AccessibilityService`, overlay window, foreground service). Those permissions
sit commented out at the bottom of `AndroidManifest.xml`. Next phase.

---

## Troubleshooting the two build errors you hit

Both are resource errors. Run the checker from **this** folder first:

```bash
python check-res.py
```

It prints `duplicates ... none` / `unresolved refs ... none` when the project is
healthy. This copy passes both.

### `Duplicate resources [color/ic_launcher_background]`

The colour was defined **twice** — once in `res/values/colors.xml`, once in a
separate `res/values/ic_launcher_background.xml`.

**v5 makes this error impossible.** The icon background colour is now called
`maatram_icon_bg` and lives only in `colors.xml`. Nothing in this project
defines or references `ic_launcher_background` any more, so even if Capacitor
regenerates `res/values/ic_launcher_background.xml`, there is nothing to collide
with — it just sits there unused.

If you still see this error, you unzipped v5 **on top of** an older copy and the
old `app/src/main/res/values/ic_launcher_background.xml` survived. Delete that
one file, then Build ▸ Clean Project. Better: unzip into an empty folder.

### `resource color/maatramBg not found` → `failed linking references`

That came from deleting `maatramBg` out of `colors.xml` while `styles.xml`
still referenced it. `AppTheme` uses it for `android:windowBackground` and
`AppTheme.NoActionBarLaunch` uses it for `windowSplashScreenBackground`.

**Never delete a colour to fix a duplicate — delete the extra *file*.**

### Stale build cache

If an error survives a fix that should have worked, Gradle is reusing merged
resources. In Android Studio: **Build → Clean Project**, then **Build → Rebuild
Project**. Or from the command line:

```bash
cd android
gradlew clean assembleDebug
```

## Current resource inventory

| Kind | Names |
|---|---|
| colours | `colorPrimary` `colorPrimaryDark` `colorAccent` `maatramBg` `ic_launcher_background` |
| styles | `AppTheme` `AppTheme.NoActionBar` `AppTheme.NoActionBarLaunch` |
| strings | `app_name` `title_activity_main` `package_name` `custom_url_scheme` |

`AppTheme.NoActionBarLaunch` inherits `Theme.SplashScreen`, which needs
`androidx.core:core-splashscreen` — already declared in `app/build.gradle`
with the version pinned in `variables.gradle`.


---

## v4 — phone display fixes + REAL app blocking (Maatram Shield)

### Display fixes
* **Zoom bug** (study room / stats "zoomed in, can't zoom out"): two causes killed —
  `user-scalable=no` on every page, and `MainActivity` now sets WebView
  `textZoom(100)` + all zoom off. The phone's system *Font size* setting was
  scaling WebView text past its boxes → overlap. Laptops never do this.
* **Stats horizontal drift**: `overflow-x:hidden` on every page + `touch-action:pan-y`
  on stats — vertical scroll only.
* **Grid blowouts**: every `1fr` grid that could overflow is now `minmax(0,1fr)`
  (seats, chat/log row, stats cards, timer mode cards, tab bars).
* **Timers**: on phones the mode cards get top padding so the red `+25/+100 pts`
  pill no longer sits on "Pomodoro"/"Deep Work". Digit roll hardened — incoming
  digits are pre-positioned off-screen, so no more "00" stacking over the clock
  when the animation lib is slow/absent.

### Maatram Shield — native Hard Lock enforcement
| File | Role |
|---|---|
| `AppBlockerService.java` | AccessibilityService. When a blocked app comes to the front during a Hard Lock, presses HOME instantly + toast. |
| `AppBlockerPlugin.java` | Capacitor bridge: `setLock / clearLock / setWhatsappAllowed / isEnabled / openSettings`. |
| `res/xml/blocker_service.xml` | Service config — window-change events only, **cannot read screen content**. |
| Manifest | `<service>` with `BIND_ACCESSIBILITY_SERVICE`. No new install-time permissions. |

Blocked while locked: Instagram (+Lite, Threads), TikTok (both builds), Snapchat,
YouTube, Facebook (+Lite), X, Reddit. **WhatsApp** (+Business) is blocked too,
but the App Gate page shows an **“Unlock WhatsApp only”** button during a lock.

Setup on the phone (one time): App Gate page shows a **Maatram Shield** card →
*Enable Shield* → Settings ▸ Accessibility ▸ Installed apps ▸ **Maatram Shield** → On.
The card disappears once it's on. Locks survive app kill: the service checks the
saved end-time itself, so blocking continues even if Maatram is swiped away.


---

## v6 - two real-device bugs fixed

### 1. Hard Lock did not actually block anything (silent failure)

`AppBlockerPlugin.setLock()` took the lock end time with
`PluginCall.getDouble("until")`. Epoch milliseconds are around 1.78e12, which is
**831x larger than `Integer.MAX_VALUE`**, so `org.json` hands it over as a
**Long**. Capacitor's `getDouble()` only handles Double / Float / Integer and
returns `null` for a Long. So:

`getDouble` -> `null` -> `call.reject(...)` -> JS `.catch()` swallowed it ->
`lock_end` stayed `0` -> the service saw "no active lock" -> YouTube opened.

No error was ever shown. That is why nothing looked wrong.

**Fixed:**
* JS now sends `until` as a **string** (`String(endTs)`); the plugin parses it
  with `Long.parseLong`, and still accepts a raw number of any type as fallback.
* `.apply()` -> `.commit()` so the service can read the value immediately.
* JS no longer swallows native errors - any failure is printed to the console
  **and** shown on the App Gate page.
* New `getStatus()` method + a live status line under the Lock button:
  `Shield ON - service running - lock 4 min left - last blocked: com.google.android.youtube`
  If blocking ever fails again, that line tells you exactly where.
* Service hardened: config also set programmatically in `onServiceConnected()`
  (some OEM builds ignore the XML), listens to `typeWindowsChanged` too,
  falls back to a HOME intent if `performGlobalAction` is refused, logs to
  logcat under tag **`MaatramShield`**, and now also covers YouTube Music /
  YouTube Go.

### 2. Stats page would not scroll

v4 added `html, body { overflow-x: hidden }` to stop sideways drift. On the root
element `overflow-x:hidden` turns `<html>` into a scroll container, and in the
Android WebView that killed **vertical** scrolling - the graphs were unreachable.

**Fixed:** switched to `overflow-x: clip`, which clips sideways overflow without
creating a scroll container, so vertical scrolling behaves normally. The
`touch-action: pan-y` override on stats was removed as well - zoom is already
blocked by the viewport tag and by `setSupportZoom(false)` in `MainActivity`.

### Checking it on the phone

1. App Gate page -> the status line under the Lock button should read
   `Shield ON - service running`.
2. Start a 5 min Hard Lock. The line should switch to `lock 5 min left`.
3. Open YouTube -> it should bounce to the home screen with a toast.
4. Still stuck? `adb logcat -s MaatramShield` prints every connect and block.
