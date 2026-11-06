#!/system/bin/sh
# NEMESIS type=execute: update the Tilepad package in place (installs it if missing).
# The old preuninstall leaves /system/app/Tilepad.apk alone during an update and
# the new postinstall rewrites it, so the app keeps its data.
. "${0%/*}/lib.sh"

[ -f "$PKGZIP" ] || { say "FAIL: missing $PKGZIP"; finish 1; }
refuse_if_launchpad
refuse_if_hmgapi
if pkg_installed; then
    run "$NEMESIS" install --update "$PKGZIP" || finish 1
else
    say "$PKG_ID not installed; installing"
    run "$NEMESIS" install "$PKGZIP" || finish 1
fi
verify_installed
RC=$?
finish $RC
