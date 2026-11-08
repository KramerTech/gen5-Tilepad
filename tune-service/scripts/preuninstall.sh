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

# Restore owned system patches, DST and the OTA client before deleting the only recovery runtime.
# Failure deliberately retains the package and all verified backups.
if [ -d "$TWEAKS_STATE" ]; then
    sh "$HERE/bin/tweaks.sh" restore || {
        log "preuninstall: FAILED: tweak restoration refused; keeping recovery files and APKs"
        exit 1
    }
    # Everything is back to stock: its settings, saved Home and file backups go too.
    rm -rf "$TWEAKS_STATE"
    log "preuninstall: tweaks restored; removed $TWEAKS_STATE"
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

# Wi-Fi client: undo what the Wi-Fi tile turned on (bin/wifi-client.sh), unless HMG Leafia Plus
# is installed, whose own client option uses the same feature file. The feature file goes only
# if Tilepad added it and it's still exactly what Tilepad wrote. Takes effect after a reboot.
if [ -f "$WIFI_RECORD" ]; then
    ADDED=0; OLD_STATE=unset
    while IFS='=' read KEY VALUE; do
        case "$KEY" in added_xml) ADDED=$VALUE ;; old_wifi_state) OLD_STATE=$VALUE ;; esac
    done < "$WIFI_RECORD"
    LEAFIA=
    for APP in "$NEMESIS_APPS"/*; do
        case "${APP##*/}" in *[Ll][Ee][Aa][Ff][Ii][Aa]*) LEAFIA=${APP##*/} ;; esac
    done
    if [ -n "$LEAFIA" ]; then
        log "preuninstall: leaving the Wi-Fi client on: $LEAFIA is installed and may use it"
    else
        if [ "$ADDED" = 1 ] && [ -f "$WIFI_XML" ]; then
            wifi_client_xml > "$BACKUP/expected-wifi.xml"
            if ! same_file "$WIFI_XML" "$BACKUP/expected-wifi.xml"; then
                log "preuninstall: leaving $WIFI_XML: it changed since Tilepad added it"
            elif system_rw; then
                rm -f "$WIFI_XML"; sync; system_restore
                log "preuninstall: removed $WIFI_XML (Wi-Fi client off after reboot)"
            else
                log "preuninstall: couldn't remount /system; $WIFI_XML left in place"
            fi
        fi
        if [ "$OLD_STATE" != unset ] && [ "$OLD_STATE" != 3 ]; then
            setprop persist.wifi.state "$OLD_STATE"
            log "preuninstall: persist.wifi.state 3 -> $OLD_STATE"
        fi
    fi
fi
rm -rf "$BACKUP"

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
