#!/system/bin/sh
# Short-lived root entry point. Settings are validated data, never sourced shell.
. /data/nemesis/apps/tilepad/scripts/common.sh
if [ -z "$BOOTCLASSPATH" ] && [ -r /init.environ.rc ]; then
    while read CMD NAME VALUE; do
        [ "$CMD" = export ] && export "$NAME=$VALUE"
    done < /init.environ.rc
fi
export CLASSPATH="$DEX"
exec app_process /system/bin --nice-name=tilepad-tweaks-command com.tilepad.tweakcore.TweaksCommand "$@"
