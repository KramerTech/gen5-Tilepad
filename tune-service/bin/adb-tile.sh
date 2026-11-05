#!/system/bin/sh
# The ADB tile's root side, run by Tilepad through `nemesis root -c`.
#
#   sh /data/nemesis/apps/tilepad/bin/adb-tile.sh on|off
#
# on   sets persist.service.adb.tcp.enable=1. This firmware's init.daudio.rc reacts to that by
#      setting service.adb.tcp.port=5555 and restarting adbd, now and at every boot. It's set
#      to 0 first so the trigger fires even if the property was already 1.
# off  clears it and stops adbd (nothing in the firmware reacts to 0), with a negative live
#      port so adbd won't listen over TCP if something starts it again before a reboot.
#
# Wi-Fi is Tilepad's side (WifiRadio): it turns the client on after `on`.
. /data/nemesis/apps/tilepad/scripts/common.sh

case "$1" in
    on)
        setprop persist.service.adb.tcp.enable 0
        setprop persist.service.adb.tcp.enable 1
        [ "$(getprop persist.service.adb.tcp.enable)" = 1 ] || { echo "Couldn't set persist.service.adb.tcp.enable."; exit 1; }
        mkdir -p "${ADB_MARK%/*}" && : > "$ADB_MARK"
        log "adb-tile: on"
        ;;
    off)
        setprop persist.service.adb.tcp.enable 0
        setprop service.adb.tcp.port -1
        setprop ctl.stop adbd
        rm -f "$ADB_MARK"
        log "adb-tile: off"
        ;;
    *) echo "usage: adb-tile.sh on|off"; exit 1 ;;
esac
exit 0
