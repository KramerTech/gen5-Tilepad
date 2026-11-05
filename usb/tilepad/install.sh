#!/system/bin/sh
# NEMESIS type=execute: first-time install of the Tilepad package (key-hold service
# + /system/app/Tilepad.apk).
. "${0%/*}/lib.sh"

[ -f "$PKGZIP" ] || { say "FAIL: missing $PKGZIP"; finish 1; }
refuse_if_launchpad
if pkg_installed; then
    say "FAIL: $PKG_ID is already installed. Use the update bundle instead."
    finish 1
fi
run "$NEMESIS" install "$PKGZIP" || finish 1
verify_installed
RC=$?
finish $RC
