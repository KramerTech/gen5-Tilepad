#!/system/bin/sh
# NEMESIS preuninstall (root; services already stopped). Also runs on the OLD
# package during `nemesis install --update`, after the new package has been
# extracted to $STAGE.
. /data/nemesis/apps/tilepad/scripts/common.sh

log "preuninstall: removing daemon dalvik cache"
rm -f "$DEXCACHE"

if [ -f "$STAGE/NEMESIS.data" ]; then
    # Update: leave /system alone. The new postinstall rewrites the APK in place,
    # so Android keeps the app's data (favorites, settings).
    log "preuninstall: update in progress; leaving $SYSAPK for the new postinstall"
    exit 0
fi

# Restore owned system patches and DST before deleting the only recovery runtime.
# Failure deliberately retains the package and all verified backups.
if [ -d "$TWEAKS_STATE" ]; then
    sh "$HERE/bin/tweaks.sh" restore || {
        log "preuninstall: FAILED: tweak restoration refused; keeping recovery files and APKs"
        exit 1
    }
fi

# ADB tile: a real uninstall turns ADB over TCP off if the tile turned it on, as the tile's own
# off does (bin/adb-tile.sh). ADB that something else turned on is left alone. (An update
# returned above, so ADB stays on across updates.)
if [ -f "$ADB_MARK" ]; then
    log "preuninstall: turning ADB over TCP off (the ADB tile had it on)"
    setprop persist.service.adb.tcp.enable 0
    setprop service.adb.tcp.port -1
    setprop ctl.stop adbd
    rm -f "$ADB_MARK"
fi

if [ -f /data/nemesis/backup/tilepad/wifi-client ]; then
    # Other apps may rely on the client by now, and HMG Leafia Plus may have adopted it.
    log "preuninstall: leaving the Wi-Fi client on (turned on from the Wi-Fi tile). To turn it off,"
    log "preuninstall: delete /system/etc/permissions/android.hardware.wifi.xml and reboot."
    rm -rf /data/nemesis/backup/tilepad
fi

[ -f "$SYSAPK" ] || [ -f "$TWEAKS_SYSAPK" ] || { log "preuninstall: APKs not present"; exit 0; }
stop_app
if ! system_rw; then
    log "preuninstall: FAILED: nemesis remount rw failed; $SYSAPK left in place"
    exit 1
fi
rm -f "$SYSAPK" "$TWEAKS_SYSAPK"
sync
system_restore
if [ -f "$SYSAPK" ] || [ -f "$TWEAKS_SYSAPK" ]; then
    log "preuninstall: FAILED: could not remove $SYSAPK"
    exit 1
fi
log "preuninstall: removed $SYSAPK"
exit 0
