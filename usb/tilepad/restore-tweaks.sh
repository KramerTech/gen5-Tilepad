#!/system/bin/sh
# Standalone USB recovery if the NEMESIS package is missing.
BASE=${0%/*}
if [ -z "$BOOTCLASSPATH" ] && [ -r /init.environ.rc ]; then
    while read CMD NAME VALUE; do
        [ "$CMD" = export ] && export "$NAME=$VALUE"
    done < /init.environ.rc
fi
export CLASSPATH="$BASE/recovery/Tilepad.apk"
export TILEPAD_TWEAK_RECIPES="$BASE/recovery/patches"
exec app_process /system/bin com.tilepad.tweakcore.TweaksCommand restore
