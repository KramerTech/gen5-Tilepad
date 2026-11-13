# Shared by the USB install/update/uninstall scripts. NEMESIS runs them as
# `/system/bin/sh <USB>/tilepad/<action>.sh` (root, cwd = USB root).
# common.sh is a copy of nemesis-package/scripts/common.sh added by the bundle builder.
LIBDIR=${0%/*}
. "$LIBDIR/common.sh"
USBROOT=${LIBDIR%/*}
PKG_ID=tilepad
PKGZIP=$USBROOT/packages/$PKG_ID.zip
ACTION=${0##*/}
ACTION=${ACTION%.sh}
LOG=$USBROOT/tilepad-$ACTION.log

say() {
    LINE="$(date '+%Y-%m-%d %H:%M:%S') $*"
    echo "$LINE"
    echo "$LINE" >> "$LOG"
}

# Run a command, appending its output (including NEMESIS hook output) to the log.
run() {
    say "\$ $*"
    "$@" >> "$LOG" 2>&1
    RC=$?
    say "  -> exit $RC"
    return $RC
}

pkg_installed() { pkg_registered "$PKG_ID"; }

# Stop before changing anything if Launchpad is still on the car (see launchpad_traces).
refuse_if_launchpad() {
    TRACES=$(launchpad_traces)
    [ -z "$TRACES" ] && return 0
    say "FAIL: the original Launchpad (paytondev.launchpad) is still installed:"
    echo "$TRACES" | while read T; do say "  - $T"; done
    say "Uninstall it first (Launchpad-uninstall-usb.zip, or NEMESIS Manager > Packages), reboot, then run this again. Nothing was changed."
    finish 1
}

# Stop before changing anything if HMG-API is on the car (see hmgapi_traces).
refuse_if_hmgapi() {
    TRACES=$(hmgapi_traces)
    [ -z "$TRACES" ] && return 0
    say "FAIL: HMG-API (hmgapi) is installed, and Tilepad can't be installed alongside it:"
    echo "$TRACES" | while read T; do say "  - $T"; done
    say "Uninstall HMG-API first (NEMESIS Manager > Packages, or HMG-API's own uninstaller), reboot, then run this again. Nothing was changed."
    finish 1
}

longstar_state() {
    "$NEMESIS" service status "$PKG_ID/longstar" 2>/dev/null | {
        IFS='|' read QNAME MODE STATE PID
        echo "$STATE"
    }
}

# Package registered, /system/app copy matches the package's APK, longstar running.
verify_installed() {
    OK=0
    if pkg_installed; then say "ok: package $PKG_ID registered"
    else say "FAIL: package $PKG_ID not registered"; OK=1; fi
    if [ -f "$SYSAPK" ] && same_file "$DEX" "$SYSAPK"; then say "ok: $SYSAPK matches the package APK"
    else say "FAIL: $SYSAPK missing or does not match $DEX"; OK=1; fi
    if [ -f "$TWEAKS_SYSAPK" ] && same_file "$TWEAKSDEX" "$TWEAKS_SYSAPK"; then say "ok: bundled Tweaks APK matches"
    else say "FAIL: bundled Tweaks APK missing or does not match"; OK=1; fi
    N=0
    STATE=$(longstar_state)
    while [ "$STATE" != running ] && [ $N -lt 10 ]; do
        sleep 1
        N=$((N + 1))
        STATE=$(longstar_state)
    done
    if [ "$STATE" = running ]; then say "ok: service $PKG_ID/longstar running"
    else say "FAIL: service $PKG_ID/longstar is '${STATE:-unknown}'"; OK=1; fi
    return $OK
}

finish() {
    if [ "$1" = 0 ]; then
        say "SUCCESS. Reboot the head unit (pinhole button) to finish, then remove the USB drive."
    else
        say "FAILED. Keep this log ($LOG); NEMESIS's own log is /data/nemesis/nemesis.log (Manager > Logs > Copy to USB)."
    fi
    say "----"
    exit "$1"
}

say "==== Tilepad $ACTION (USB: $USBROOT, Android up: $(getprop sys.boot_completed))"
