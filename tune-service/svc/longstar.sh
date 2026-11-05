#!/system/bin/sh
# NEMESIS service (mode=restart): open the configured app when the steering-wheel star
# (custom) button is held.
# Runs the longstar daemon via app_process using the bundled APK purely as a dex container
# (no PackageManager registration involved). svcmgr tracks the daemon's own pid.
# It is also Tilepad's one boot hook. LongStarDaemon hosts event-driven tweak listeners
# in its own process; the ADB Wi-Fi helper below is a bounded startup operation.
. /data/nemesis/apps/tilepad/scripts/common.sh
DEVICE_NAME=daudio_wp_daudioplus_neev23_us
KEY_CODE=43
HOLD_MS=800
TARGET_COMPONENT=com.tilepad/.MainActivity
[ -r "$HERE/longstar.conf" ] && . "$HERE/longstar.conf"

# app_process needs init's framework environment (BOOTCLASSPATH); load it if svcmgr
# did not pass it through.
if [ -z "$BOOTCLASSPATH" ] && [ -r /init.environ.rc ]; then
    while read CMD NAME VALUE; do
        [ "$CMD" = export ] && export "$NAME=$VALUE"
    done < /init.environ.rc
fi

# The target is a normal framework app, so wait until Android is up before listening.
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done

export CLASSPATH="$DEX"

# ADB tile: ADB over TCP comes back at boot on its own (a persistent property), but the head unit
# boots with the CarPlay hotspot up instead of Wi-Fi. If the tile turned ADB on, turn Wi-Fi on
# too, in the background (WifiRadio retries for up to two minutes, then exits). The delay keeps
# it from racing the daemon below for the dex cache. Also runs when svcmgr restarts this
# service; WifiRadio does nothing if Wi-Fi is already on.
if [ -f "$ADB_MARK" ] && [ "$(getprop persist.service.adb.tcp.enable)" = 1 ]; then
    log "ADB is on: making sure Wi-Fi is on"
    (sleep 5; exec app_process /system/bin --nice-name=tilepad-wifi com.tilepad.WifiRadio) &
fi

log "exec daemon -> $TARGET_COMPONENT"
exec app_process /system/bin --nice-name=tilepad-longstar com.tilepad.longstar.LongStarDaemon \
    "$DEVICE_NAME" "$KEY_CODE" "$HOLD_MS" "$TARGET_COMPONENT"
