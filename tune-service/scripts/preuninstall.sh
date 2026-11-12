#!/system/bin/sh
# NEMESIS preuninstall (root; services already stopped). Also runs on the OLD
# package during `nemesis install --update`, after the new package has been
# extracted to $STAGE.
. /data/nemesis/apps/tilepad/scripts/common.sh
. /data/nemesis/apps/tilepad/scripts/remove.sh

# An update has the incoming package in $STAGE. NEMESIS deletes the stage after every install, but
# one killed mid-install leaves it behind, so a stage alone isn't proof: NEMESIS runs this hook
# directly, and its command line says which it is. Only a clear uninstall or wipe overrides the
# stage; anything unreadable keeps the update path, which changes nothing.
updating() {
    [ -f "$STAGE/NEMESIS.data" ] || return 1
    case "$(cat /proc/$PPID/cmdline 2>/dev/null)" in
        *nemesis?uninstall*|*nemesisuninstall*|*nemesis?wipe*|*nemesiswipe*)
            log "preuninstall: ignoring a stage left by an interrupted install; this is an uninstall"
            return 1 ;;
    esac
    return 0
}

if updating; then
    # Update: leave /system alone. The new postinstall rewrites the APK in place,
    # so Android keeps the app's data (favorites, settings), and reconciles owned tweak files.
    rm -f "$DEXCACHE"
    log "preuninstall: update in progress; leaving $SYSAPK for the new postinstall"
    exit 0
fi

remove_tilepad sh "$HERE/bin/tweaks.sh" restore || exit 1
exit 0
