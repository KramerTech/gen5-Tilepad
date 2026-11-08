#!/bin/sh
# tools/compat/patch_check.sh <reference system/> <reference recipe suffix> <new system/> <new recipe suffix> <work dir>
# e.g. ... offline-headunit/20261031/system v015 <log>/system kia-v015 /tmp/work   (run from the repo root)
# Applies each image's consent-<suffix> and right-<suffix> recipes with Tilepad's own
# PatchManager.transform (every consent/nav/popover combination), disassembles the all-on result
# with -a 18, and compares the two images' patched classes with resource IDs masked. Then checks
# the patched methods' bytes with methods.py. Needs build/tweaks-host-tests (tools/tweaks/test-host.sh).
set -eu
REF=$1 REFS=$2 NEW=$3 NEWS=$4 WORK=$5
BJ=${BAKSMALI:-$HOME/.local/share/smali/baksmali-2.5.2.jar}
CLASSES=build/tweaks-host-tests
[ -d "$CLASSES/com/tilepad/tweakcore" ] || sh tools/tweaks/test-host.sh > /dev/null
rm -rf "$WORK"; mkdir -p "$WORK/classes"
javac -nowarn --release 8 -cp "$CLASSES" -d "$WORK/classes" tools/compat/PatchCheck.java 2>/dev/null
for side in ref new; do
    if [ $side = ref ]; then sys=$REF suffix=$REFS; else sys=$NEW suffix=$NEWS; fi
    java -cp "$CLASSES:$WORK/classes" PatchCheck "$sys" tune-service/tweaks/patches "$WORK/patched-$side" \
        "consent-$suffix.properties" "right-$suffix.properties"
    java -jar "$BJ" d -a 18 --allow-odex-opcodes --classes 'Lcom/mobis/splitscreen/right/k/r;,Lcom/mobis/splitscreen/right/r/i;' \
        -o "$WORK/dis-$side/RightScreenHome" "$WORK/patched-$side/RightScreenHome.odex" 2>/dev/null
    java -jar "$BJ" d -a 18 --allow-odex-opcodes --classes 'Lcom/android/systemui/consentscreen/D;' \
        -o "$WORK/dis-$side/SystemUI" "$WORK/patched-$side/SystemUI.odex" 2>/dev/null
done
python3 -I tools/compat/compare.py "$WORK/dis-ref" "$WORK/dis-new"
python3 -I tools/compat/methods.py tools/tweaks/firmware_patches.py "$REF" "$NEW"
