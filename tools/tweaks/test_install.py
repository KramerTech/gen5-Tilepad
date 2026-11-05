#!/usr/bin/env python3
"""Run the actual install/uninstall hooks against temporary filesystem fixtures."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class InstallHooks(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="tilepad-hooks-")
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        for folder in ("system/app", "system/etc/permissions", "package/app", "package/bin", "work", "stage", "state", "backup", "apps/tilepad"):
            (self.base / folder).mkdir(parents=True, exist_ok=True)
        self.common = self.base / "common.sh"
        self.common.write_text('''
HERE="$FIXTURE/package"
DEX="$HERE/app/Tilepad.apk"
TWEAKSDEX="$HERE/app/Tweaks.apk"
SYSAPK="$FIXTURE/system/app/Tilepad.apk"
TWEAKS_SYSAPK="$FIXTURE/system/app/TilepadTweaks.apk"
WORK="$FIXTURE/work"
STAGE="$FIXTURE/stage"
TWEAKS_STATE="$FIXTURE/state"
BACKUP="$FIXTURE/backup"
ADB_MARK="$BACKUP/adb.on"
WIFI_RECORD="$BACKUP/wifi-client"
WIFI_XML="$FIXTURE/system/etc/permissions/android.hardware.wifi.xml"
NEMESIS_APPS="$FIXTURE/apps"
wifi_client_xml() {
    echo '<permissions>'
    echo '    <feature name="android.hardware.wifi" />'
    echo '</permissions>'
}
setprop() { echo "$1=$2" >> "$FIXTURE/props"; }
DEXCACHE="$FIXTURE/dexcache"
APP_PACKAGE=com.tilepad
log() { echo "$*"; }
toast() { echo "$*"; }
same_file() { cmp -s "$1" "$2"; }
file_size() { wc -c < "$1"; }
system_free() { echo 100000000; }
system_rw() { echo rw >> "$FIXTURE/mounts"; }
system_restore() { echo ro >> "$FIXTURE/mounts"; }
stop_app() { :; }
chown() { :; }
mv() {
    case "$2" in
      *TilepadTweaks.apk)
        if [ "$FAIL_SECOND" = 1 ] && [ ! -f "$FIXTURE/failed" ]; then
            touch "$FIXTURE/failed"; return 1
        fi ;;
    esac
    command mv "$@"
}
''')
        self.write("package/app/Tilepad.apk", b"new launcher")
        self.write("package/app/Tweaks.apk", b"new tweaks")
        self.write("system/app/Tilepad.apk", b"old launcher")
        self.write("system/app/TilepadTweaks.apk", b"old tweaks")
        self.write("package/bin/tweaks.sh", b'echo "$*" > "$FIXTURE/restored"\n[ "$RESTORE_FAIL" != 1 ]\n')

    def write(self, name, content):
        path = self.base / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    def run_hook(self, name, **extra):
        source = (ROOT / "tune-service/scripts" / name).read_text()
        source = source.replace(". /data/nemesis/apps/tilepad/scripts/common.sh", '. "$FIXTURE/common.sh"')
        source = source.replace("/system/app/", str(self.base / "system/app") + "/")
        source = source.replace("/data/nemesis/backup/tilepad", str(self.base / "unused-backup"))
        script = self.base / name
        script.write_text(source)
        env = dict(os.environ, FIXTURE=str(self.base), **extra)
        return subprocess.run(["sh", str(script)], env=env, capture_output=True, text=True)

    def test_install_places_both_apks_and_is_idempotent(self):
        self.assertEqual(0, self.run_hook("postinstall.sh").returncode)
        self.assertEqual(b"new launcher", (self.base / "system/app/Tilepad.apk").read_bytes())
        self.assertEqual(b"new tweaks", (self.base / "system/app/TilepadTweaks.apk").read_bytes())
        mounts = (self.base / "mounts").read_bytes()
        self.assertEqual(0, self.run_hook("postinstall.sh").returncode)
        self.assertEqual(mounts, (self.base / "mounts").read_bytes())

    def test_second_apk_failure_restores_both_previous_versions(self):
        self.assertNotEqual(0, self.run_hook("postinstall.sh", FAIL_SECOND="1").returncode)
        self.assertEqual(b"old launcher", (self.base / "system/app/Tilepad.apk").read_bytes())
        self.assertEqual(b"old tweaks", (self.base / "system/app/TilepadTweaks.apk").read_bytes())
        self.assertTrue((self.base / "mounts").read_text().endswith("ro\n"))

    def test_update_does_not_restore_tweaks_or_remove_either_apk(self):
        self.write("stage/NEMESIS.data", b"update")
        self.assertEqual(0, self.run_hook("preuninstall.sh").returncode)
        self.assertFalse((self.base / "restored").exists())
        self.assertTrue((self.base / "system/app/Tilepad.apk").exists())
        self.assertTrue((self.base / "system/app/TilepadTweaks.apk").exists())

    def test_real_uninstall_restores_tweaks_before_removing_apks(self):
        self.assertEqual(0, self.run_hook("preuninstall.sh").returncode)
        self.assertEqual("restore\n", (self.base / "restored").read_text())
        self.assertFalse((self.base / "system/app/Tilepad.apk").exists())
        self.assertFalse((self.base / "system/app/TilepadTweaks.apk").exists())

    WIFI_XML = b'<permissions>\n    <feature name="android.hardware.wifi" />\n</permissions>\n'

    def props(self):
        path = self.base / "props"
        return path.read_text() if path.exists() else ""

    def test_real_uninstall_removes_tweak_state_and_records(self):
        self.write("state/config.properties", b"chargeaway=true\nhomeLat=1\nhomeLon=2\n")
        self.write("backup/adb.on", b"")
        self.assertEqual(0, self.run_hook("preuninstall.sh").returncode)
        self.assertFalse((self.base / "state").exists())
        self.assertFalse((self.base / "backup").exists())

    def test_uninstall_turns_off_adb_the_tile_turned_on(self):
        self.write("backup/adb.on", b"")
        self.assertEqual(0, self.run_hook("preuninstall.sh").returncode)
        self.assertIn("persist.service.adb.tcp.enable=0", self.props())
        self.assertIn("ctl.stop=adbd", self.props())

    def test_uninstall_leaves_adb_it_did_not_turn_on(self):
        self.assertEqual(0, self.run_hook("preuninstall.sh").returncode)
        self.assertNotIn("adb", self.props())

    def test_uninstall_removes_the_wifi_client_tilepad_added(self):
        self.write("system/etc/permissions/android.hardware.wifi.xml", self.WIFI_XML)
        self.write("backup/wifi-client", b"date=x\nadded_xml=1\nold_wifi_state=1\n")
        self.assertEqual(0, self.run_hook("preuninstall.sh").returncode)
        self.assertFalse((self.base / "system/etc/permissions/android.hardware.wifi.xml").exists())
        self.assertIn("persist.wifi.state=1", self.props())

    def test_uninstall_keeps_a_wifi_client_it_did_not_add_or_that_changed(self):
        self.write("system/etc/permissions/android.hardware.wifi.xml", self.WIFI_XML)
        self.write("backup/wifi-client", b"added_xml=0\nold_wifi_state=3\n")
        self.assertEqual(0, self.run_hook("preuninstall.sh").returncode)
        self.assertTrue((self.base / "system/etc/permissions/android.hardware.wifi.xml").exists())
        self.assertNotIn("wifi", self.props())
        self.write("system/etc/permissions/android.hardware.wifi.xml", b"<permissions>someone else's</permissions>\n")
        self.write("backup/wifi-client", b"added_xml=1\nold_wifi_state=3\n")
        self.assertEqual(0, self.run_hook("preuninstall.sh").returncode)
        self.assertTrue((self.base / "system/etc/permissions/android.hardware.wifi.xml").exists())

    def test_uninstall_keeps_the_wifi_client_while_leafia_is_installed(self):
        (self.base / "apps/hmg-leafia-plus").mkdir()
        self.write("system/etc/permissions/android.hardware.wifi.xml", self.WIFI_XML)
        self.write("backup/wifi-client", b"added_xml=1\nold_wifi_state=1\n")
        self.assertEqual(0, self.run_hook("preuninstall.sh").returncode)
        self.assertTrue((self.base / "system/etc/permissions/android.hardware.wifi.xml").exists())
        self.assertNotIn("wifi", self.props())

    def test_failed_restore_preserves_recovery_runtime(self):
        self.write("state/config.properties", b"consent=true\n")
        self.assertNotEqual(0, self.run_hook("preuninstall.sh", RESTORE_FAIL="1").returncode)
        self.assertTrue((self.base / "state/config.properties").exists())
        self.assertTrue((self.base / "system/app/Tilepad.apk").exists())
        self.assertTrue((self.base / "system/app/TilepadTweaks.apk").exists())


if __name__ == "__main__":
    unittest.main()
