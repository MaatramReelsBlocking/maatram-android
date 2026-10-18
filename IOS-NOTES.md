# iOS notes

## What works the same
All pages in `www/` (timers, points, leaderboard, study room, stats, socials), Firebase Auth and Firestore (web SDK inside WKWebView), haptics (`navigator.vibrate` is mapped to the Haptics plugin), splash, status bar, keyboard.

## Google sign-in on iOS
Same flow as Android, no native code. `native.js` opens `auth-bridge.html` in Safari. The page returns to `maatram://auth?idt=...`. The `maatram` URL scheme is registered in `ios/App/App/Info.plist`; `native.js` reads the token from the `appUrlOpen` event (and `getLaunchUrl()` on a cold start) and calls `signInWithCredential`. Sign in with Apple is only demanded by App Store review, not for installing on your own phone.

## Hard Lock on iOS (implemented, built, NOT yet tested on a phone)
`ios/App/App/AppBlockerPlugin.swift` is the iOS twin of the Android plugin (same JS name `AppBlocker`):
- `requestAuthorization`: Screen Time permission (FamilyControls, individual).
- `pickApps({kind:"block"|"allow"})`: Apple's app picker. Apple hides app identities, so there is no way to say "WhatsApp"; the user picks. The "allow" list is what the WhatsApp unlock button turns on.
- `setLock({until})`: shields the picked apps (ManagedSettings) and schedules the DeviceActivity monitor to end it. A running lock can only be extended.
- `MaatramMonitor` extension ends the lock at the time even if Maatram is closed. iOS refuses schedules under about 15 minutes; shorter locks are ended the next time Maatram opens (`expireIfDue`).
- `MaatramShield` extension draws the lock screen over blocked apps.
- Entitlement `com.apple.developer.family-controls` + app group `group.com.maatram.app` on the app and both extensions.
Differences from Android: user can disable Screen Time access in Settings; friction gate (pay points to open an app) does not exist on iOS; the "last blocked app" is not available.
Distribution (TestFlight/App Store) needs Apple's manual approval of the Family Controls (Distribution) entitlement per bundle id; development builds on your own registered phone do not.

## Android-only, off on iOS
- Friction gate and its points tolls, `lastBlocked`, accessibility-service Shield, `MainActivity` token parking, hardware back button.
