#!/bin/bash
# Usage: verify_ipa.sh App.ipa   (run on macOS). Fails if the app or an extension is missing,
# unsigned, or lacks the Family Controls entitlement / app group.
set -euo pipefail
IPA="$1"; D=$(mktemp -d); unzip -q "$IPA" -d "$D"
APP="$D/Payload/App.app"; fail=0
check() {
  local b="$1" label="$2"
  codesign --verify --strict "$b" || { echo "FAIL $label: bad signature"; fail=1; return; }
  local ent; ent=$(codesign -d --entitlements :- "$b" 2>/dev/null)
  for k in com.apple.developer.family-controls group.com.maatram.app get-task-allow; do
    echo "$ent" | grep -q "$k" && echo "ok   $label: $k" || { echo "FAIL $label: missing $k"; fail=1; }
  done
  security cms -D -i "$b/embedded.mobileprovision" > "$D/p.plist" 2>/dev/null || { echo "FAIL $label: no embedded profile"; fail=1; return; }
  echo "info $label: profile '$(/usr/libexec/PlistBuddy -c 'Print Name' "$D/p.plist")' team $(/usr/libexec/PlistBuddy -c 'Print TeamIdentifier:0' "$D/p.plist") devices $(/usr/libexec/PlistBuddy -c 'Print ProvisionedDevices' "$D/p.plist" | grep -c '^ *[0-9a-fA-F-]\{20,\}')"
}
check "$APP" App
for x in MaatramMonitor MaatramShield; do
  [ -d "$APP/PlugIns/$x.appex" ] && check "$APP/PlugIns/$x.appex" "$x" || { echo "FAIL $x.appex not packaged"; fail=1; }
done
exit $fail
