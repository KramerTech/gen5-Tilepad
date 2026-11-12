# Sourced after common.sh. remove_tilepad <restore command...> undoes everything Tilepad changed
# outside its package: owned tweak files, DST and the OTA client (the restore command), the ADB
# and Wi-Fi tiles' changes, both APKs, and what an interrupted install or tweak write left behind.
# Used by preuninstall on a real uninstall, and by the USB uninstall when the package is missing.
# A failed restore returns 1 with nothing else changed, keeping the recovery runtime and backups.

remove_tilepad() {
    # Restore owned system patches, DST and the OTA client before deleting the only recovery runtime.
    if [ -d "$TWEAKS_STATE" ]; then
        "$@" || {
            log "remove: FAILED: tweak restoration refused; keeping recovery files and APKs"
            return 1
        }
        # Everything is back to stock: its settings, saved Home and file backups go too.
        rm -rf "$TWEAKS_STATE"
        log "remove: tweaks restored; removed $TWEAKS_STATE"
    fi

    # ADB tile: a real uninstall turns ADB over TCP off if the tile turned it on, as the tile's own
    # off does (bin/adb-tile.sh). ADB that something else turned on is left alone.
    if [ -f "$ADB_MARK" ]; then
        log "remove: turning ADB over TCP off (the ADB tile had it on)"
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
            log "remove: leaving the Wi-Fi client on: $LEAFIA is installed and may use it"
        else
            if [ "$ADDED" = 1 ] && [ -f "$WIFI_XML" ]; then
                wifi_client_xml > "$BACKUP/expected-wifi.xml"
                if ! same_file "$WIFI_XML" "$BACKUP/expected-wifi.xml"; then
                    log "remove: leaving $WIFI_XML: it changed since Tilepad added it"
                elif system_rw; then
                    rm -f "$WIFI_XML"; sync; system_restore
                    log "remove: removed $WIFI_XML (Wi-Fi client off after reboot)"
                else
                    log "remove: couldn't remount /system; $WIFI_XML left in place"
                fi
            fi
            if [ "$OLD_STATE" != unset ] && [ "$OLD_STATE" != 3 ]; then
                setprop persist.wifi.state "$OLD_STATE"
                log "remove: persist.wifi.state 3 -> $OLD_STATE"
            fi
        fi
    fi
    rm -rf "$BACKUP"

    # Install staging and rollback copies (postinstall refuses to run again while a failed
    # rollback's copies are there), and the dex cache the restore command may have recreated.
    rm -rf "$WORK"
    rm -f "$DEXCACHE"

    # Both APKs, plus temporary files an interrupted install or tweak write left in /system
    # (`<file>.tilepad-new`, renamed over the file once complete; the restore just verified
    # every owned file, so any left now are stale).
    LEFT=
    for F in "$SYSAPK" "$TWEAKS_SYSAPK" "$SYSAPK.tilepad-new" "$TWEAKS_SYSAPK.tilepad-new" \
        /system/priv-app/SystemUI.odex.tilepad-new /system/app/RightScreenHome.odex.tilepad-new \
        /system/usr/keylayout/*.tilepad-new; do
        [ -f "$F" ] && LEFT="$LEFT $F"
    done
    [ -n "$LEFT" ] || { log "remove: no APKs or leftovers in /system"; return 0; }
    stop_app
    if ! system_rw; then
        log "remove: FAILED: nemesis remount rw failed;$LEFT left in place"
        return 1
    fi
    for F in $LEFT; do rm -f "$F"; done
    sync
    system_restore
    for F in $LEFT; do
        [ -f "$F" ] && { log "remove: FAILED: could not remove $F"; return 1; }
    done
    log "remove: removed$LEFT"
    return 0
}
