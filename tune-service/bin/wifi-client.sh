#!/system/bin/sh
# Turns on the head unit's Wi-Fi client (joining a network), for units set up with the
# CarPlay/Android Auto hotspot only. It is the same switch as HMG Leafia Plus's opt-in
# LEAFIA-WIFI-CLIENT: the android.hardware.wifi feature, which Hyundai's Wi-Fi service and
# settings app read as "client supported", and persist.wifi.state=3 ("client + wireless").
# Android reads features at boot, so it takes effect after a reboot.
#
#   sh /data/nemesis/apps/tilepad/bin/wifi-client.sh enable    (root; Tilepad runs it via nemesis root -c)
#
# Exit 0: added, or already there. Exit 1: failed, nothing half-written left behind.
. /data/nemesis/apps/tilepad/scripts/common.sh

XML=$WIFI_XML
TMP=${WIFI_XML%/*}/.android.hardware.wifi.xml.tilepad
# What Tilepad changed, so a real uninstall can undo it (scripts/preuninstall.sh).
RECORD=$WIFI_RECORD

fail() { rm -f "$TMP"; system_restore; log "wifi-client: FAILED: $*"; echo "$*"; exit 1; }

[ "$1" = enable ] || { echo "usage: wifi-client.sh enable"; exit 1; }

OLD_STATE=$(getprop persist.wifi.state)
ADDED=0
if [ -f "$XML" ]; then
    log "wifi-client: $XML already present"
else
    system_rw || fail "Couldn't make /system writable (nemesis remount rw)."
    wifi_client_xml > "$TMP" || fail "Couldn't write $TMP."
    chmod 644 "$TMP"
    chown 0.0 "$TMP" 2>/dev/null || chown 0:0 "$TMP"
    mv "$TMP" "$XML" || fail "Couldn't create $XML."
    sync
    system_restore
    [ -s "$XML" ] || fail "$XML is missing after writing it."
    ADDED=1
    log "wifi-client: added $XML"
fi

if [ "$OLD_STATE" != 3 ]; then
    setprop persist.wifi.state 3
    [ "$(getprop persist.wifi.state)" = 3 ] || fail "Couldn't set persist.wifi.state=3."
    log "wifi-client: persist.wifi.state ${OLD_STATE:-unset} -> 3"
fi

if [ "$ADDED" = 1 ] || [ "$OLD_STATE" != 3 ]; then
    mkdir -p "${RECORD%/*}"
    {
        echo "date=$(date '+%Y-%m-%d %H:%M:%S')"
        echo "added_xml=$ADDED"
        echo "old_wifi_state=${OLD_STATE:-unset}"
    } > "$RECORD"
fi
echo "Wi-Fi client turned on. Reboot to finish."
exit 0
