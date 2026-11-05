# Shared by the hooks and the service. Toolbox only (no grep/awk/busybox on KitKat).
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
umask 022
HERE=/data/nemesis/apps/tilepad
NEMESIS=/system/xbin/nemesis
# The signed Tilepad APK. The longstar daemon uses it as its dex container
# (CLASSPATH under app_process), and postinstall places a copy in /system/app so
# PackageManager registers the visible app.
DEX=$HERE/app/Tilepad.apk
# dalvik-cache entry app_process may create for that dex (named after its path).
DEXCACHE=/data/dalvik-cache/data@nemesis@apps@tilepad@app@Tilepad.apk@classes.dex
# Default system installs: the launcher and the standalone Tweaks settings APK.
# Explicitly enabled static tweaks also own verified files through PatchManager.
SYSAPK=/system/app/Tilepad.apk
TWEAKSDEX=$HERE/app/Tweaks.apk
TWEAKS_SYSAPK=/system/app/TilepadTweaks.apk
TWEAKS_STATE=/data/nemesis/backup/tilepad-tweaks
# Everything else (staging, verification copies, rollback backup) lives here.
# NEMESIS's root shell has SETUID/SETGID but no DAC override on the car.
# /data/local/tmp is shell-owned 0771, so uid 0 cannot create a staging directory
# there. Use NEMESIS's writable backup parent; keep failed rollback copies durable.
WORK=/data/nemesis/backup/tilepad-install
# NEMESIS extracts an incoming package here before running the old package's
# uninstall hooks, so its presence during preuninstall means "update".
STAGE=/data/local/tmp/nemesis/stage/tilepad
APP_PACKAGE=com.tilepad
# Tilepad's records of what it changed outside its package, so they survive updates and a real
# uninstall can undo them (and then removes this directory).
BACKUP=/data/nemesis/backup/tilepad
# Exists while ADB over TCP is on because the ADB tile turned it on (bin/adb-tile.sh).
ADB_MARK=$BACKUP/adb.on
# Written by bin/wifi-client.sh when it added the Wi-Fi client feature or changed the Wi-Fi mode.
WIFI_RECORD=$BACKUP/wifi-client
WIFI_XML=/system/etc/permissions/android.hardware.wifi.xml
NEMESIS_APPS=/data/nemesis/apps
# The feature file bin/wifi-client.sh writes; uninstall only removes a file that still matches it.
wifi_client_xml() {
    echo '<permissions>'
    echo '    <feature name="android.hardware.wifi" />'
    echo '</permissions>'
}

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') tilepad $*"; }

android_up() { [ "$(getprop sys.boot_completed)" = 1 ]; }

# Hyundai's on-screen toast, for hooks run from NEMESIS Manager's Package Store, where nobody
# reads the log. am needs the framework environment, which hooks may start without.
toast() {
    android_up || return 0
    if [ -z "$BOOTCLASSPATH" ] && [ -r /init.environ.rc ]; then
        while read CMD NAME VALUE; do
            [ "$CMD" = export ] && export "$NAME=$VALUE"
        done < /init.environ.rc
    fi
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" \
        --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1
    return 0
}

# 0 if two files have identical contents. The head unit's toolbox cmp has no -s; it prints
# differences to stdout and exits 1 (2 on errors), so silence it and use the exit status.
same_file() { cmp "$1" "$2" >/dev/null 2>&1; }

# Size in bytes of a file, from toolbox `ls -l` (perms owner group size date time name).
file_size() {
    set -- $(ls -l "$1" 2>/dev/null)
    case "$4" in ''|*[!0-9]*) echo 0 ;; *) echo "$4" ;; esac
}

# Free bytes on /system from toolbox df ("Filesystem Size Used Free Blksize",
# sizes like 312.4M). Prints nothing if the output cannot be parsed.
system_free() {
    df /system 2>/dev/null | while read FS SIZE USED FREE BLK; do
        [ "$FS" = /system ] || continue
        case "$FREE" in
            *K) MUL=1024 ;; *M) MUL=1048576 ;; *G) MUL=1073741824 ;; *) continue ;;
        esac
        NUM=${FREE%?}
        INT=${NUM%%.*}
        FRAC=0
        [ "$NUM" != "$INT" ] && FRAC=${NUM#*.}
        FRAC=${FRAC%${FRAC#?}}
        case "$INT$FRAC" in ''|*[!0-9]*) continue ;; esac
        echo $(( INT * MUL + FRAC * MUL / 10 ))
    done
}

# 0 if the last /system entry in /proc/mounts is mounted read-only.
system_is_ro() {
    RO=1
    while read DEV MNT FS OPTS REST; do
        [ "$MNT" = /system ] || continue
        case "$OPTS" in ro|ro,*) RO=0 ;; *) RO=1 ;; esac
    done < /proc/mounts
    return $RO
}

# Make /system writable only if it is read-only now, and remember to put it back.
REMOUNTED=0
system_rw() {
    if system_is_ro; then
        "$NEMESIS" remount rw || return 1
        REMOUNTED=1
    fi
    return 0
}
system_restore() {
    if [ "$REMOUNTED" = 1 ]; then
        "$NEMESIS" remount ro || log "WARN: could not remount /system ro"
        REMOUNTED=0
    fi
}

# 0 if NEMESIS lists package id $1.
pkg_registered() {
    "$NEMESIS" pkg-list 2>/dev/null | {
        while IFS='|' read ID REST; do
            [ "$ID" = "$1" ] && exit 0
        done
        exit 1
    }
}

# Tilepad replaces the original Launchpad (its TUNE-hold service and preconditioning backend),
# so it refuses to install next to it. Prints one line per trace still on the car; prints nothing if it is gone.
LAUNCHPAD_ID=paytondev.launchpad
launchpad_traces() {
    pkg_registered "$LAUNCHPAD_ID" && echo "NEMESIS package $LAUNCHPAD_ID"
    [ -d "/data/nemesis/apps/$LAUNCHPAD_ID" ] && echo "/data/nemesis/apps/$LAUNCHPAD_ID"
    [ -f /system/app/Launchpad.apk ] && echo /system/app/Launchpad.apk
    if android_up; then
        case "$(pm path com.paytondev.launchpad 2>/dev/null)" in
            package:*) echo "Android app com.paytondev.launchpad" ;;
        esac
    fi
    return 0
}

stop_app() {
    android_up && am force-stop "$APP_PACKAGE" >/dev/null 2>&1
    android_up && am force-stop com.tilepad.tweaks >/dev/null 2>&1
    return 0
}
