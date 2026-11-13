#!/system/bin/sh
# NEMESIS preinstall (root, cwd = staging dir). Refuses a fresh install while the original
# Launchpad or HMG-API is still on the car, so installing from NEMESIS Manager is covered too,
# not just the USB bundle. NEMESIS 1.16+ also refuses while either package is registered
# (depends=!paytondev.launchpad,!hmgapi); this hook catches what that check can't see
# (leftovers, HMG-API installed without NEMESIS) and older NEMESIS. NEMESIS has already removed the old Tilepad by the time it runs
# this hook during an update, so refusing then would leave neither app; updates only warn
# (the USB update script refuses before NEMESIS starts).
. ./scripts/common.sh

refuse() {  # <name> <traces>
    [ -z "$2" ] && return 0
    if pkg_registered tilepad; then
        log "preinstall: WARN: $1 is still installed ($2); updating anyway"
        return 0
    fi
    log "preinstall: FAILED: uninstall $1 first, then reboot. Found: $2"
    echo "Uninstall $1 first, then reboot." >&2
    exit 1
}
refuse "the original Launchpad (paytondev.launchpad)" "$(launchpad_traces)"
refuse "HMG-API (hmgapi)" "$(hmgapi_traces)"
exit 0
