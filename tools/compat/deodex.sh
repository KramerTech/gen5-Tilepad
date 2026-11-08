#!/bin/sh
# tools/compat/deodex.sh <system-dir> <out-dir>
# Deodexes every file Tilepad's gates and patches depend on, in parallel, with the flags that
# work on this firmware (-a 18; .odex names in -b; only the target, plus hcan for infobank).
# <system-dir> is a captured /system or a compatibility log's system/ folder.
# Exits non-zero if any file fails or baksmali reports an unresolved instruction.
set -u
SYS=$(cd "$1" && pwd); OUT=$2
BJ=${BAKSMALI:-$HOME/.local/share/smali/baksmali-2.5.2.jar}
BCP=core.odex:conscrypt.odex:okhttp.odex:core-junit.odex:bouncycastle.odex:ext.odex:framework.odex:framework2.odex:telephony-common.odex:voip-common.odex:mms-common.odex:android.policy.odex:services.odex:apache-xml.odex:webviewchromium.odex:automotive.odex
mkdir -p "$OUT/odex" "$OUT/log"
cp "$SYS"/framework/*.odex "$OUT/odex/"
for app in priv-app/SystemUI app/RightScreenHome app/HKMC_TAppEV3; do
    [ -f "$SYS/$app.odex" ] && cp "$SYS/$app.odex" "$OUT/odex/"
done
for t in automotive automotive-service framework framework2 com.infobank com.hkmc.ext.hcan services SystemUI RightScreenHome HKMC_TAppEV3; do
    [ -f "$OUT/odex/$t.odex" ] || { echo "missing $t.odex" >&2; continue; }
    case $t in
        automotive|framework|framework2|services) b=$BCP ;;
        com.infobank) b=$BCP:com.hkmc.ext.hcan.odex:$t.odex ;;
        *) b=$BCP:$t.odex ;;
    esac
    java -jar "$BJ" x -a 18 -d "$OUT/odex" -b "$b" -o "$OUT/smali/$t" "$OUT/odex/$t.odex" > "$OUT/log/$t.log" 2>&1 &
done
wait
status=0
for f in "$OUT"/log/*.log; do
    n=$(grep -c Exception "$f")
    [ "$n" = 0 ] || { echo "FAIL $(basename "$f" .log): $n exceptions (see $f)" >&2; status=1; }
done
[ $status = 0 ] && echo "deodexed into $OUT/smali"
exit $status
