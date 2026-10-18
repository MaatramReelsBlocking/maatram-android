# iOS notes

## What works the same
All pages in `www/` (timers, points, leaderboard, study room, stats, socials), Firebase Auth and Firestore (web SDK inside WKWebView), haptics (`navigator.vibrate` is mapped to the Haptics plugin), splash, status bar, keyboard.

## Google sign-in on iOS
Same flow as Android, no native code. `native.js` opens `auth-bridge.html` in Safari. The page returns to `maatram://auth?idt=...`. The `maatram` URL scheme is registered in `ios/App/App/Info.plist`; `native.js` reads the token from the `appUrlOpen` event (and `getLaunchUrl()` on a cold start) and calls `signInWithCredential`. Sign in with Apple is only demanded by App Store review, not for installing on your own phone.

## Android-only, off on iOS
- `AppBlocker` plugin (Hard Lock, Shield, friction gate, WhatsApp allow): Java + AccessibilityService. The Shield card in `app-gate.html` stays hidden because it is only shown when this plugin exists.
- `maatram://` token parking in `MainActivity` (iOS uses the JS path above).
- Hardware back button handler (never fires on iOS).

## Hard Lock on iOS: what Apple allows
Frameworks: FamilyControls (authorisation + app picker), ManagedSettings (shield apps), DeviceActivity (schedule/monitor, runs in an app extension).
- Needs the entitlement `com.apple.developer.family-controls`, per app AND per extension (DeviceActivityMonitor, ShieldConfiguration, ShieldAction each need their own).
- Development entitlement: on by default in Xcode, enough to run on your own device.
- Distribution (TestFlight / App Store / Ad Hoc): manual Apple review of a request form, no fixed timeline, often weeks. Apple has rejected productivity/MDM-looking apps.
- Needs native Swift (SwiftUI `FamilyActivityPicker`, extension targets), plus a Mac with Xcode to create the extension targets and sign them. Not possible with an unsigned sideloaded .ipa from Sideloadly: the entitlement must come from a provisioning profile of a paid account.
- Users can turn the shield off in Settings, so it is weaker than the Android lock.
Not implemented here: it cannot be compiled or tested without macOS and a paid account, and shipping untested extension targets would break the CI build.
