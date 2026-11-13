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
NEMESIS="$FIXTURE/nemesis"
pkg_registered() { false; }
getprop() { :; }
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

    def remove_script(self):
        """The real remove.sh with /system under the fixture."""
        source = (ROOT / "nemesis-package/scripts/remove.sh").read_text()
        return source.replace("/system/", str(self.base / "system") + "/")

    def run_hook(self, name, parent=None, **extra):
        """Runs a hook, directly or (parent) as a child of a stand-in `nemesis <parent...>` process."""
        source = (ROOT / "nemesis-package/scripts" / name).read_text()
        source = source.replace(". /data/nemesis/apps/tilepad/scripts/common.sh", '. "$FIXTURE/common.sh"')
        source = source.replace(". /data/nemesis/apps/tilepad/scripts/remove.sh", '. "$FIXTURE/remove.sh"')
        source = source.replace("/system/app/", str(self.base / "system/app") + "/")
        source = source.replace("/data/nemesis/backup/tilepad", str(self.base / "unused-backup"))
        (self.base / "remove.sh").write_text(self.remove_script())
        script = self.base / name
        script.write_text(source)
        env = dict(os.environ, FIXTURE=str(self.base), **extra)
        if parent is None:
            return subprocess.run(["sh", str(script)], env=env, capture_output=True, text=True)
        nemesis = self.base / "nemesis"
        nemesis.write_text(f'sh "{script}"\n')
        return subprocess.run(["sh", str(nemesis), *parent], env=env, capture_output=True, text=True)

    def preinstall(self, registered="", files=()):
        """The real preinstall and common.sh, with car paths under the fixture and a stand-in NEMESIS."""
        stage = self.base / "pre"
        (stage / "scripts").mkdir(parents=True, exist_ok=True)
        common = (ROOT / "nemesis-package/scripts/common.sh").read_text()
        for car in ("/data/hmgapi/", "/system/bin/", "/system/app/", "/data/nemesis/apps/"):
            common = common.replace(car, str(self.base) + car)
        common += '\nexport PATH="$HOST_PATH"\nNEMESIS="$FIXTURE/nemesis"\n'
        (stage / "scripts/common.sh").write_text(common)
        (stage / "scripts/preinstall.sh").write_text((ROOT / "nemesis-package/scripts/preinstall.sh").read_text())
        nemesis = self.base / "nemesis"
        nemesis.write_text('#!/bin/sh\n[ "$1" = pkg-list ] && for id in $REGISTERED; do echo "$id|Name|0|application|1.0|x"; done\n')
        nemesis.chmod(0o755)
        for name in files:
            self.write(name, b"x")
        env = dict(os.environ, FIXTURE=str(self.base), HOST_PATH=os.environ["PATH"], REGISTERED=registered)
        return subprocess.run(["sh", "scripts/preinstall.sh"], cwd=stage, env=env, capture_output=True, text=True)

    def test_preinstall_refuses_hmgapi_installed_with_or_without_nemesis(self):
        for registered, files in (("hmgapi", ()), ("", ("data/hmgapi/installed.txt",)), ("", ("system/bin/hmgapid",))):
            with self.subTest(registered=registered, files=files):
                result = self.preinstall(registered, files)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn("Uninstall HMG-API (hmgapi) first", result.stderr)
                for name in files:
                    (self.base / name).unlink()

    def test_preinstall_only_warns_about_hmgapi_during_an_update(self):
        # NEMESIS has already removed the old Tilepad by then; refusing would leave neither.
        result = self.preinstall("tilepad", ("data/hmgapi/installed.txt",))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("WARN: HMG-API (hmgapi) is still installed", result.stdout)

    def test_preinstall_allows_a_car_without_launchpad_or_hmgapi(self):
        result = self.preinstall()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

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
        self.write("state/config.properties", b"ota=true\n")
        self.write("state/ota-client.disabled", b"com.hkmc.system.app.OTAClient")
        self.assertEqual(0, self.run_hook("preuninstall.sh").returncode)
        self.assertFalse((self.base / "restored").exists())
        self.assertEqual(b"ota=true\n", (self.base / "state/config.properties").read_bytes())
        self.assertTrue((self.base / "state/ota-client.disabled").exists())
        self.assertTrue((self.base / "system/app/Tilepad.apk").exists())
        self.assertTrue((self.base / "system/app/TilepadTweaks.apk").exists())

    def test_a_stage_left_by_an_interrupted_install_doesnt_turn_an_uninstall_into_an_update(self):
        self.write("stage/NEMESIS.data", b"left behind")
        self.write("state/config.properties", b"consent=true\n")
        result = self.run_hook("preuninstall.sh", parent=("uninstall", "tilepad"))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("restore\n", (self.base / "restored").read_text())
        self.assertFalse((self.base / "state").exists())
        self.assertFalse((self.base / "system/app/Tilepad.apk").exists())

    def test_nemesis_install_update_with_a_stage_is_an_update(self):
        self.write("stage/NEMESIS.data", b"update")
        self.write("state/config.properties", b"consent=true\n")
        result = self.run_hook("preuninstall.sh", parent=("install", "--update", "/mnt/usb/packages/tilepad.zip"))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.base / "restored").exists())
        self.assertTrue((self.base / "state/config.properties").exists())
        self.assertTrue((self.base / "system/app/Tilepad.apk").exists())

    def test_uninstall_clears_what_interrupted_installs_and_writes_left(self):
        # A failed rollback's copies (postinstall refuses to run again while they're there), temp files
        # from writes killed before their rename, and the dex cache the restore recreates.
        self.write("work/rollback.failed", b"")
        self.write("work/Tilepad.previous", b"old")
        (self.base / "system/app/Tilepad.apk").unlink()
        for name in ("system/app/Tilepad.apk.tilepad-new", "system/priv-app/SystemUI.odex.tilepad-new",
                     "system/app/RightScreenHome.odex.tilepad-new", "system/usr/keylayout/daudio_x_base.kl.tilepad-new"):
            self.write(name, b"partial")
        self.write("package/bin/tweaks.sh", b'echo "$*" > "$FIXTURE/restored"; touch "$FIXTURE/dexcache"\n')
        result = self.run_hook("preuninstall.sh")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("restore\n", (self.base / "restored").read_text())
        self.assertFalse((self.base / "work").exists())
        self.assertFalse((self.base / "dexcache").exists())
        self.assertEqual([], sorted(str(p.relative_to(self.base)) for p in (self.base / "system").rglob("*") if p.is_file()))

    def test_postinstall_reconciles_owned_tweak_files_without_failing_the_install(self):
        result = self.run_hook("postinstall.sh")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("reconcile\n", (self.base / "restored").read_text())
        (self.base / "restored").unlink()
        result = self.run_hook("postinstall.sh", RESTORE_FAIL="1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("WARN: tweak files not reconciled", result.stdout)
        (self.base / "state").rmdir()
        (self.base / "restored").unlink()
        self.assertEqual(0, self.run_hook("postinstall.sh").returncode)
        self.assertFalse((self.base / "restored").exists())

    def usb_uninstall(self):
        """The real USB uninstall, lib.sh and remove.sh, with fixture paths and a drive-side runtime stand-in."""
        usb = self.base / "usb/tilepad"
        usb.mkdir(parents=True)
        (usb / "common.sh").write_text(self.common.read_text())
        (usb / "lib.sh").write_text((ROOT / "usb/tilepad/lib.sh").read_text())
        (usb / "uninstall.sh").write_text((ROOT / "usb/tilepad/uninstall.sh").read_text())
        (usb / "remove.sh").write_text(self.remove_script())
        (usb / "restore-tweaks.sh").write_text('echo restore > "$FIXTURE/restored"\n')
        env = dict(os.environ, FIXTURE=str(self.base))
        return subprocess.run(["sh", str(usb / "uninstall.sh")], env=env, capture_output=True, text=True)

    def test_usb_uninstall_removes_everything_when_the_package_is_missing(self):
        # An update interrupted after NEMESIS removed the old version: no package, everything else left.
        self.write("state/config.properties", b"consent=true\n")
        self.write("backup/adb.on", b"")
        self.write("work/rollback.failed", b"")
        result = self.usb_uninstall()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("restore\n", (self.base / "restored").read_text())
        for gone in ("state", "backup", "work", "system/app/Tilepad.apk", "system/app/TilepadTweaks.apk"):
            self.assertFalse((self.base / gone).exists(), gone)
        self.assertIn("ctl.stop=adbd", self.props())

    def test_usb_uninstall_after_everything_is_gone_changes_nothing(self):
        for name in ("system/app/Tilepad.apk", "system/app/TilepadTweaks.apk"):
            (self.base / name).unlink()
        (self.base / "state").rmdir()
        result = self.usb_uninstall()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.base / "restored").exists())
        self.assertFalse((self.base / "mounts").exists())

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
        self.write("state/config.properties", b"consent=true\nota=true\n")
        self.write("state/ota-client.disabled", b"com.hkmc.system.app.OTAClient")
        self.assertNotEqual(0, self.run_hook("preuninstall.sh", RESTORE_FAIL="1").returncode)
        self.assertTrue((self.base / "state/config.properties").exists())
        self.assertTrue((self.base / "state/ota-client.disabled").exists())
        self.assertTrue((self.base / "system/app/Tilepad.apk").exists())
        self.assertTrue((self.base / "system/app/TilepadTweaks.apk").exists())


if __name__ == "__main__":
    unittest.main()
