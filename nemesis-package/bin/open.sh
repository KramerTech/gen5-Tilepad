#!/system/bin/sh
# NEMESIS Manager > Packages > Launch (and `nemesis pkg-launch tilepad`):
# open the same component the star hold opens.
. /data/nemesis/apps/tilepad/scripts/common.sh
TARGET_COMPONENT=com.tilepad/.MainActivity
[ -r "$HERE/longstar.conf" ] && . "$HERE/longstar.conf"
am start -n "$TARGET_COMPONENT" -f 0x10000000
