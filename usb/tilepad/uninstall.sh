#!/system/bin/sh
# NEMESIS type=execute: remove the Tilepad package and everything it changed.
# Does not remove NEMESIS itself.
. "${0%/*}/lib.sh"
. "$LIBDIR/remove.sh"

# A stale stage dir from an interrupted install would make preuninstall think this
# is an update and keep the APK. It lives in tmp, so clearing it is safe.
rm -rf "$STAGE"

if pkg_installed; then
    run "$NEMESIS" uninstall "$PKG_ID" || finish 1
else
    say "$PKG_ID not installed as a NEMESIS package"
fi

# Whatever NEMESIS's uninstall didn't cover: a missing package (an update interrupted after the
# old version was removed), a manual (README/ADB) install, or what an interrupted install left.
# Uses the runtime and recipes on this drive. Changes nothing once everything is gone.
run remove_tilepad sh "$LIBDIR/restore-tweaks.sh" || finish 1
# That runtime ran from the drive: drop the dex cache Dalvik made for it (named after its path).
RUNTIME=$LIBDIR/recovery/Tilepad.apk
case "$RUNTIME" in /*) ;; *) RUNTIME=$PWD/$RUNTIME ;; esac
CACHE=; OLDIFS=$IFS; IFS=/
for PART in ${RUNTIME#/}; do CACHE="$CACHE${CACHE:+@}$PART"; done
IFS=$OLDIFS
rm -f "/data/dalvik-cache/$CACHE@classes.dex"

OK=0
if pkg_installed; then say "FAIL: $PKG_ID still registered"; OK=1; else say "ok: $PKG_ID not registered"; fi
if [ -f "$SYSAPK" ]; then say "FAIL: $SYSAPK still present"; OK=1; else say "ok: $SYSAPK removed"; fi
if [ -f "$TWEAKS_SYSAPK" ]; then say "FAIL: Tweaks APK still present"; OK=1; else say "ok: Tweaks APK removed"; fi
if [ -d "$TWEAKS_STATE" ]; then say "FAIL: $TWEAKS_STATE still present"; OK=1; else say "ok: tweaks restored"; fi
finish $OK
