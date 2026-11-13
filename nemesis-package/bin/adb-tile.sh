#!/system/bin/sh
# The ADB tile's root side, run by Tilepad through `nemesis root -c`.
#
#   sh /data/nemesis/apps/tilepad/bin/adb-tile.sh on|off|wifi
#
# on   sets persist.service.adb.tcp.enable=1. This firmware's init.daudio.rc reacts to that by
#      setting service.adb.tcp.port=5555 and restarting adbd, now and at every boot. It's set
#      to 0 first so the trigger fires even if the property was already 1.
# off  clears it and stops adbd (nothing in the firmware reacts to 0), with a negative live
#      port so adbd won't listen over TCP if something starts it again before a reboot.
# wifi turns the Wi-Fi client on (the CarPlay hotspot off first), once, after `on`. That needs
#      the system's network permissions, which the Tilepad app doesn't hold, so WifiRadio runs
#      as uid 1000 under app_process (as root it can't write the dex cache). Prints why not, if not.
. /data/nemesis/apps/tilepad/scripts/common.sh

case "$1" in
    on)
        # init.daudio.rc sets the port from a separate oneshot service and restarts adbd without
        # waiting for it, so adbd can start with the old port (-1 after off) and skip TCP. Set it
        # first.
        setprop service.adb.tcp.port 5555
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
    wifi)
        if [ -z "$BOOTCLASSPATH" ] && [ -r /init.environ.rc ]; then
            while read CMD NAME VALUE; do
                [ "$CMD" = export ] && export "$NAME=$VALUE"
            done < /init.environ.rc
        fi
        exec /system/xbin/nemesis root 1000 -c "BOOTCLASSPATH=$BOOTCLASSPATH CLASSPATH=$DEX app_process /system/bin --nice-name=tilepad-wifi com.tilepad.WifiRadio once"
        ;;
    *) echo "usage: adb-tile.sh on|off|wifi"; exit 1 ;;
esac
exit 0
