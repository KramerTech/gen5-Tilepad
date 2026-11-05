#!/bin/sh
# Run locally. Does not connect to or modify a vehicle.
# Signs with keystore/tilepad.jks (password in keystore/password) unless TILEPAD_KEYSTORE
# and TILEPAD_STORE_PASSWORD are set. Both live outside git (see .gitignore).
# If tools/signing-cert.sha256 (or $TILEPAD_CERT_PIN_FILE) exists, the APK's signer must
# match it, so a wrong key fails here instead of producing an APK that won't install over
# the previous one. CI's throwaway-key test build points TILEPAD_CERT_PIN_FILE elsewhere.
set -eu
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_HOME
BT="$ANDROID_HOME/build-tools/${BUILD_TOOLS_VERSION:-36.0.0}"
[ -x "$BT/apksigner" ] || { echo "Install Android build-tools ${BUILD_TOOLS_VERSION:-36.0.0}." >&2; exit 1; }
: "${TILEPAD_KEYSTORE:=$PWD/keystore/tilepad.jks}"
[ -f "$TILEPAD_KEYSTORE" ] || { echo "Missing $TILEPAD_KEYSTORE (set TILEPAD_KEYSTORE)." >&2; exit 1; }
if [ -z "${TILEPAD_STORE_PASSWORD:-}" ] && [ -f keystore/password ]; then
    TILEPAD_STORE_PASSWORD=$(cat keystore/password)
fi
: "${TILEPAD_STORE_PASSWORD:?Set TILEPAD_STORE_PASSWORD}"
: "${TILEPAD_KEY_PASSWORD:=$TILEPAD_STORE_PASSWORD}"
: "${TILEPAD_KEY_ALIAS:=tilepad}"
export TILEPAD_STORE_PASSWORD TILEPAD_KEY_PASSWORD
if [ "${1:-}" = --sdk-only ]; then
    python3 tools/tweaks/build_sdk.py
else
    ./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease \
        :tweaks:testDebugUnitTest :tweaks:lintRelease :tweaks:assembleRelease
fi
sh tools/tweaks/test-host.sh
mkdir -p dist
# Explicit v1 SHA1 signing: what Android 4.4's PackageManager verifies.
JARSIGNER=jarsigner
[ -z "${JAVA_HOME:-}" ] || JARSIGNER="$JAVA_HOME/bin/jarsigner"
for APP in Tilepad Tweaks; do
MODULE=app
[ "$APP" = Tweaks ] && MODULE=tweaks
python3 tools/kitkat_repackage.py "$MODULE/build/outputs/apk/release/$MODULE-release-unsigned.apk" "dist/$APP-signing.apk"
"$JARSIGNER" -keystore "$TILEPAD_KEYSTORE" -storepass:env TILEPAD_STORE_PASSWORD \
    -keypass:env TILEPAD_KEY_PASSWORD -digestalg SHA1 -sigalg SHA1withRSA \
    "dist/$APP-signing.apk" "$TILEPAD_KEY_ALIAS"
"$BT/zipalign" -f 4 "dist/$APP-signing.apk" "dist/$APP.apk"
rm -f "dist/$APP-signing.apk"
"$BT/apksigner" verify --min-sdk-version 19 --verbose --print-certs "dist/$APP.apk" > dist/.verify
cat dist/.verify
PIN=${TILEPAD_CERT_PIN_FILE:-tools/signing-cert.sha256}
if [ -f "$PIN" ]; then
    want=$(tr -d ' \n' < "$PIN")
    grep -qi "certificate SHA-256 digest: $want\$" dist/.verify || {
        rm -f dist/.verify "dist/$APP.apk"
        echo "Signed with the wrong key: expected certificate SHA-256 $want ($PIN)." >&2
        exit 1
    }
fi
rm -f dist/.verify
done
