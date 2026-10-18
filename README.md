

## iOS

Same web app (`www/`), wrapped by Capacitor 6 (`ios/`). Bundle id `com.maatram.app`, iOS 13+.
No Mac needed: `.github/workflows/ios.yml` builds on a GitHub macOS machine.

**Install on an iPhone from Windows**

1. GitHub → Actions → "Build iOS" → latest run → download `maatram-ios-unsigned` and unzip it to get `Maatram-unsigned.ipa`.
2. Install iTunes and iCloud from apple.com (not the Microsoft Store versions) and [Sideloadly](https://sideloadly.io) on the PC.
3. Plug the iPhone in with a cable, tap Trust, then on the phone turn on Settings → Privacy & Security → Developer Mode (restarts the phone).
4. Drag the `.ipa` into Sideloadly, enter your Apple ID, press Start. Then Settings → General → VPN & Device Management → trust your Apple ID.
5. A free Apple ID signature lasts 7 days, so repeat step 4 weekly. A paid Apple Developer account lasts a year.

**Signed build with Hard Lock (needs the paid Apple Developer Program).** Hard Lock uses Screen Time (Family Controls), so the app has two extensions and needs a properly signed build; Sideloadly re-signing removes the entitlement. Follow `IOS-SIGNING.md`.

**Not on iOS.** The friction gate (it intercepts app opens) has no iOS equivalent. Hard Lock/Shield use Screen Time instead, see `IOS-NOTES.md`.
