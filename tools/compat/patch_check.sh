#!/bin/sh
# tools/compat/patch_check.sh <reference system/> <reference recipe suffix> <new system/> <new recipe suffix> <work dir>
# e.g. ... offline-headunit/20261031/system v015 <log>/system kia-v015 /tmp/work   (run from the repo root)
# Applies each image's consent-<suffix> and right-<suffix> recipes with Tilepad's own
# PatchManager.transform (every consent/nav/popover combination), disassembles the all-on result
# with -a 18, and compares the two images' patched classes with resource IDs masked. Then checks
# the patched methods' bytes with methods.py. Needs build/tweaks-host-tests (tools/tweaks/test-host.sh).
# A release obfuscated differently (firmware_patches.py NAMES) is compared with names masked, under
# the reference's class paths; methods.py is then skipped, since offsets and names both moved.
set -eu
REF=$1 REFS=$2 NEW=$3 NEWS=$4 WORK=$5
BJ=${BAKSMALI:-$HOME/.local/share/smali/baksmali-2.5.2.jar}
CLASSES=build/tweaks-host-tests
[ -d "$CLASSES/com/tilepad/tweakcore" ] || sh tools/tweaks/test-host.sh > /dev/null
rm -rf "$WORK"; mkdir -p "$WORK/classes"
javac -nowarn --release 8 -cp "$CLASSES" -d "$WORK/classes" tools/compat/PatchCheck.java 2>/dev/null
# The RightScreenHome classes each release patches: "<nav/popover class> <OSD class>".
classes() { python3 -I -c 'import sys; sys.path.insert(0, "tools/tweaks"); import firmware_patches as f
n = f.NAMES.get(sys.argv[1], f.V015_NAMES); print(n["right"], n["osd"])' "$1"; }
file() { echo "$1" | sed 's/^L//; s/;$/.smali/'; }
set -- $(classes "$REFS") $(classes "$NEWS")
REF_RIGHT=$1 REF_OSD=$2 NEW_RIGHT=$3 NEW_OSD=$4
for side in ref new; do
    if [ $side = ref ]; then sys=$REF suffix=$REFS right=$REF_RIGHT osd=$REF_OSD; else sys=$NEW suffix=$NEWS right=$NEW_RIGHT osd=$NEW_OSD; fi
    java -cp "$CLASSES:$WORK/classes" PatchCheck "$sys" tune-service/tweaks/patches "$WORK/patched-$side" \
        "consent-$suffix.properties" "right-$suffix.properties"
    java -jar "$BJ" d -a 18 --allow-odex-opcodes --classes "$right,$osd" \
        -o "$WORK/dis-$side/RightScreenHome" "$WORK/patched-$side/RightScreenHome.odex" 2>/dev/null
    java -jar "$BJ" d -a 18 --allow-odex-opcodes --classes 'Lcom/android/systemui/consentscreen/D;' \
        -o "$WORK/dis-$side/SystemUI" "$WORK/patched-$side/SystemUI.odex" 2>/dev/null
done
if [ "$REF_RIGHT $REF_OSD" = "$NEW_RIGHT $NEW_OSD" ]; then
    python3 -I tools/compat/compare.py "$WORK/dis-ref" "$WORK/dis-new"
    python3 -I tools/compat/methods.py tools/tweaks/firmware_patches.py "$REF" "$NEW"
else
    D=$WORK/dis-new/RightScreenHome; mkdir -p "$WORK/moved"
    mv "$D/$(file "$NEW_RIGHT")" "$WORK/moved/right.smali"; mv "$D/$(file "$NEW_OSD")" "$WORK/moved/osd.smali"
    mkdir -p "$(dirname "$D/$(file "$REF_RIGHT")")" "$(dirname "$D/$(file "$REF_OSD")")"
    mv "$WORK/moved/right.smali" "$D/$(file "$REF_RIGHT")"; mv "$WORK/moved/osd.smali" "$D/$(file "$REF_OSD")"
    find "$D" -type d -empty -delete
    python3 -I tools/compat/compare.py "$WORK/dis-ref" "$WORK/dis-new" --mask-names
    echo "methods.py skipped: $NEWS is obfuscated differently from $REFS; read the --show diff above"
fi
