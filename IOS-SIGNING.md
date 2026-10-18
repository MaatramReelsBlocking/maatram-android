# Signed iOS build: what you must do (Windows only, no Mac)

Everything in the repo is ready. Apple requires these steps from you, because they need your Apple account.
Hard Lock uses the Family Controls entitlement, so a **paid Apple Developer Program** membership is required
(the free Apple ID cannot get it; check the current price on developer.apple.com/programs).

## A. Apple Developer portal (developer.apple.com → Account → Certificates, Identifiers & Profiles)
1. **Identifiers → App Groups → +** create `group.com.maatram.app`.
2. **Identifiers → App IDs → +** create three App IDs (type App): `com.maatram.app`, `com.maatram.app.monitor`, `com.maatram.app.shield`.
   For EACH one tick the capabilities **App Groups** (Configure → select the group above) and **Family Controls (Development)**. Save.
3. **Devices → +** add your iPhone: platform iOS, its UDID. Get the UDID on Windows: plug the phone in, open iTunes (or the Apple Devices app), click the phone's serial number text until the UDID shows, copy it.
4. **Certificate (made on Windows with OpenSSL, which comes with Git for Windows):**
   ```
   openssl genrsa -out maatram.key 2048
   openssl req -new -key maatram.key -out maatram.csr -subj "/emailAddress=YOUR_EMAIL/CN=Maatram/C=IN"
   ```
   Certificates → + → **Apple Development** → upload `maatram.csr` → download `development.cer`, then:
   ```
   openssl x509 -inform DER -in development.cer -out dev.pem
   openssl pkcs12 -export -inkey maatram.key -in dev.pem -out maatram.p12 -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1 -password pass:CHOOSE_A_PASSWORD
   ```
5. **Profiles → + → iOS App Development**, three times (one per App ID above), each with your Apple Development certificate and your iPhone. Download the three `.mobileprovision` files.

## B. GitHub secrets (repo → Settings → Secrets and variables → Actions)
Base64 a file in PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\file"))`

| Secret | Value |
|---|---|
| `IOS_CERT_P12_BASE64` | base64 of `maatram.p12` |
| `IOS_CERT_PASSWORD` | the password you chose |
| `IOS_PROFILE_APP_BASE64` | base64 of the `com.maatram.app` profile |
| `IOS_PROFILE_MONITOR_BASE64` | base64 of the `.monitor` profile |
| `IOS_PROFILE_SHIELD_BASE64` | base64 of the `.shield` profile |
| `APPLE_TEAM_ID` | 10-character Team ID (Account → Membership) |
| `KEYCHAIN_PASSWORD` | any random string |

## C. Build
Actions → **Build iOS** → Run workflow → branch `ios-support`. When it finishes, artifact **`maatram-ios-signed`** holds
`Maatram-signed.ipa` and `signing-report.txt` (the verification of signature, entitlements, profile and device count for the app and both extensions; the job fails if anything is missing).

## D. Install on the iPhone from Windows
Do NOT use Sideloadly or AltStore for this build: they re-sign the app with a free Apple ID, which strips the Family Controls entitlement and Screen Time stops working.
Install the already-signed `.ipa` with a tool that installs without re-signing, for example the open-source `pymobiledevice3` (`pip install pymobiledevice3`, then `pymobiledevice3 apps install Maatram-signed.ipa`) or iMazing. I could not test either from here; confirm the tool you pick does not re-sign.
Prerequisites: iTunes or Apple Devices app installed, phone connected and trusted, Settings → Privacy & Security → Developer Mode ON. A development profile expires after a year (or when the certificate does).

## E. First launch
Open Maatram → App Gate → Allow Screen Time (Face ID / passcode prompt) → Choose apps to lock → start a Hard Lock → open a chosen app: the Maatram shield screen should appear.
