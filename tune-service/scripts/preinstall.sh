#!/system/bin/sh
# NEMESIS preinstall (root, cwd = staging dir). Refuses a fresh install while the original
# Launchpad is still on the car, so installing from NEMESIS Manager is covered too, not
# just the USB bundle. NEMESIS 1.16+ also refuses while the Launchpad package is registered
# (depends=!paytondev.launchpad); this hook catches the leftovers that check can't see, and
# older NEMESIS. NEMESIS has already removed the old Tilepad by the time it runs
# this hook during an update, so refusing then would leave neither app; updates only warn
# (the USB update script refuses before NEMESIS starts).
. ./scripts/common.sh

TRACES=$(launchpad_traces)
[ -z "$TRACES" ] && exit 0
if pkg_registered tilepad; then
    log "preinstall: WARN: Launchpad is still installed ($TRACES); updating anyway"
    exit 0
fi
log "preinstall: FAILED: uninstall the original Launchpad (paytondev.launchpad) first, then reboot. Found: $TRACES"
echo "Uninstall Launchpad (paytondev.launchpad) first, then reboot." >&2
exit 1
