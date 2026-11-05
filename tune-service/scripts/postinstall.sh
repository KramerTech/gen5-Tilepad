#!/system/bin/sh
# Install both signed APKs as one staged operation; keep old versions for rollback.
. /data/nemesis/apps/tilepad/scripts/common.sh

fail() { log "postinstall: FAILED: $*"; exit 1; }
[ -f "$DEX" ] && [ -f "$TWEAKSDEX" ] || fail "bundle must contain Tilepad.apk and Tweaks.apk"
if [ -f "$SYSAPK" ] && [ -f "$TWEAKS_SYSAPK" ] \
    && same_file "$DEX" "$SYSAPK" && same_file "$TWEAKSDEX" "$TWEAKS_SYSAPK"; then
    log "postinstall: both APKs already current"
    exit 0
fi

[ ! -f "$WORK/rollback.failed" ] || fail "previous rollback needs recovery; copies kept in $WORK"
rm -rf "$WORK"
mkdir -p "$WORK" || fail "cannot create staging directory"
NEW_SIZE=0
OLD_SIZE=0
for NAME in Tilepad TilepadTweaks; do
    SRC=$DEX
    [ "$NAME" = TilepadTweaks ] && SRC=$TWEAKSDEX
    DST=/system/app/$NAME.apk
    cat "$SRC" > "$WORK/$NAME.apk" && same_file "$SRC" "$WORK/$NAME.apk" || fail "cannot stage $NAME"
    NEW_SIZE=$(( NEW_SIZE + $(file_size "$WORK/$NAME.apk") ))
    if [ -f "$DST" ]; then
        cat "$DST" > "$WORK/$NAME.previous" && same_file "$DST" "$WORK/$NAME.previous" || fail "cannot back up $NAME"
        OLD_SIZE=$(( OLD_SIZE + $(file_size "$DST") ))
    fi
done
FREE=$(system_free)
# Atomic replacements temporarily need the OLD and NEW copies simultaneously.
if [ -n "$FREE" ] && [ "$FREE" -lt $(( NEW_SIZE + 1048576 )) ]; then
    fail "not enough space for both APKs; nothing changed"
fi
stop_app
system_rw || fail "cannot remount /system"
FAILED=0
for NAME in Tilepad TilepadTweaks; do
    DST=/system/app/$NAME.apk
    if cat "$WORK/$NAME.apk" > "$DST.tilepad-new" \
        && chown 0:0 "$DST.tilepad-new" && chmod 0644 "$DST.tilepad-new" \
        && same_file "$WORK/$NAME.apk" "$DST.tilepad-new" \
        && mv "$DST.tilepad-new" "$DST" && sync && same_file "$WORK/$NAME.apk" "$DST"; then
        log "postinstall: installed $DST"
    else
        FAILED=1
        break
    fi
done
if [ "$FAILED" = 1 ]; then
    ROLLBACK_FAILED=0
    for NAME in Tilepad TilepadTweaks; do
        DST=/system/app/$NAME.apk
        rm -f "$DST.tilepad-new"
        if [ -f "$WORK/$NAME.previous" ]; then
            cat "$WORK/$NAME.previous" > "$DST" && chown 0:0 "$DST" && chmod 0644 "$DST" \
                && same_file "$WORK/$NAME.previous" "$DST" || ROLLBACK_FAILED=1
        else
            rm -f "$DST" || ROLLBACK_FAILED=1
        fi
    done
    sync
    system_restore
    if [ "$ROLLBACK_FAILED" = 1 ]; then
        touch "$WORK/rollback.failed"
        fail "APK rollback incomplete; recovery copies retained in $WORK"
    fi
    rm -rf "$WORK"
    fail "APK install failed; both previous APKs restored"
fi
system_restore
rm -rf "$WORK"
toast "Tilepad and Tweaks installed. Reboot the head unit to finish."
exit 0
