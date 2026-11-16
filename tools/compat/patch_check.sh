#!/bin/sh
# tools/compat/patch_check.sh <reference system/> <reference recipe suffix> <new system/> <new recipe suffix> <work dir>
# e.g. ... offline-headunit/20261031/system v015 <log>/system kia-v015 /tmp/work   (run from the repo root)
# Applies each image's consent-<suffix> and right-<suffix> recipes with Tilepad's own
# PatchManager.transform (every consent/nav/popover combination), deodexes the all-on result
# with -a 18, and compares the two images' patched classes with resource IDs masked. Then checks
# the patched methods' bytes with methods.py. Needs build/tweaks-host-tests (tools/tweaks/test-host.sh).
# A release obfuscated differently (firmware_patches.py NAMES) is compared with names masked, under
# the reference's class paths; methods.py is then skipped, since offsets and names both moved.
set -eu
REF=$1 REFS=$2 NEW=$3 NEWS=$4 WORK=$5
BJ=${BAKSMALI:-$HOME/.local/share/smali/baksmali-2.5.2.jar}
BCP=core.odex:conscrypt.odex:okhttp.odex:core-junit.odex:bouncycastle.odex:ext.odex:framework.odex:framework2.odex:telephony-common.odex:voip-common.odex:mms-common.odex:android.policy.odex:services.odex:apache-xml.odex:webviewchromium.odex:automotive.odex
CLASSES=build/tweaks-host-tests
[ -d "$CLASSES/com/tilepad/tweakcore" ] || sh tools/tweaks/test-host.sh > /dev/null
rm -rf "$WORK"; mkdir -p "$WORK/classes"
javac -nowarn --release 8 -cp "$CLASSES" -d "$WORK/classes" tools/compat/PatchCheck.java 2>/dev/null
# The RightScreenHome classes each release patches: "<nav/popover class> <OSD class>".
classes() { python3 -I -c 'import sys; sys.path.insert(0, "tools/tweaks"); import firmware_patches as f
n = f.NAMES.get(sys.argv[1], f.V015_NAMES); print(n["right"], n["osd"])' "$1"; }
consent_class() { python3 -I -c 'import sys; sys.path.insert(0, "tools/tweaks"); import firmware_patches as f
n = f.NAMES.get(sys.argv[1], f.V015_NAMES); print(n.get("consentClass", "Lcom/android/systemui/consentscreen/D;"))' "$1"; }
file() { echo "$1" | sed 's/^L//; s/;$/.smali/'; }
set -- $(classes "$REFS") $(classes "$NEWS")
REF_RIGHT=$1 REF_OSD=$2 NEW_RIGHT=$3 NEW_OSD=$4
MASK_NAMES=$(python3 -I -c 'import sys; sys.path.insert(0, "tools/tweaks"); import firmware_patches as f
print("yes" if sys.argv[1] in f.NAMES or sys.argv[2] in f.NAMES else "no")' "$REFS" "$NEWS")
for side in ref new; do
    if [ $side = ref ]; then sys=$REF suffix=$REFS right=$REF_RIGHT osd=$REF_OSD; else sys=$NEW suffix=$NEWS right=$NEW_RIGHT osd=$NEW_OSD; fi
    java -cp "$CLASSES:$WORK/classes" PatchCheck "$sys" nemesis-package/tweaks/patches "$WORK/patched-$side" \
        "consent-$suffix.properties" "right-$suffix.properties"
    # Resolve quickened calls against this image's dependencies: obfuscation can reorder vtables.
    mkdir -p "$WORK/odex-$side"
    cp "$sys"/framework/*.odex "$WORK/odex-$side/"
    cp "$WORK/patched-$side/RightScreenHome.odex" "$WORK/patched-$side/SystemUI.odex" "$WORK/odex-$side/"
    java -jar "$BJ" x -a 18 -d "$WORK/odex-$side" -b "$BCP:RightScreenHome.odex" --classes "$right,$osd" \
        -o "$WORK/dis-$side/RightScreenHome" "$WORK/patched-$side/RightScreenHome.odex" > "$WORK/right-$side.log" 2>&1
    consent=$(consent_class "$suffix")
    java -jar "$BJ" x -a 18 -d "$WORK/odex-$side" -b "$BCP:SystemUI.odex" --classes "$consent" \
        -o "$WORK/dis-$side/SystemUI" "$WORK/patched-$side/SystemUI.odex" > "$WORK/consent-$side.log" 2>&1
    if grep -q Exception "$WORK/right-$side.log" "$WORK/consent-$side.log"; then
        echo "FAIL: unresolved instructions (see $WORK/*-$side.log)" >&2; exit 1
    fi
    test -s "$WORK/dis-$side/RightScreenHome/$(file "$right")"
    test -s "$WORK/dis-$side/RightScreenHome/$(file "$osd")"
    test -s "$WORK/dis-$side/SystemUI/$(file "$consent")"
done
if [ "$MASK_NAMES" = no ]; then
    python3 -I tools/compat/compare.py "$WORK/dis-ref" "$WORK/dis-new"
    python3 -I tools/compat/methods.py tools/tweaks/firmware_patches.py "$REF" "$NEW"
else
    if [ "$REF_RIGHT $REF_OSD" != "$NEW_RIGHT $NEW_OSD" ]; then
        D=$WORK/dis-new/RightScreenHome; mkdir -p "$WORK/moved"
        mv "$D/$(file "$NEW_RIGHT")" "$WORK/moved/right.smali"; mv "$D/$(file "$NEW_OSD")" "$WORK/moved/osd.smali"
        mkdir -p "$(dirname "$D/$(file "$REF_RIGHT")")" "$(dirname "$D/$(file "$REF_OSD")")"
        mv "$WORK/moved/right.smali" "$D/$(file "$REF_RIGHT")"; mv "$WORK/moved/osd.smali" "$D/$(file "$REF_OSD")"
        find "$D" -type d -empty -delete
    fi
    python3 -I tools/compat/compare.py "$WORK/dis-ref" "$WORK/dis-new" --mask-names
    echo "methods.py skipped: per-release names; compare patched methods and stock differences separately"
fi
