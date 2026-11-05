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
        for folder in ("system/app", "package/app", "package/bin", "work", "stage", "state"):
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
ADB_MARK="$FIXTURE/adb.on"
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

    def test_failed_restore_preserves_recovery_runtime(self):
        self.assertNotEqual(0, self.run_hook("preuninstall.sh", RESTORE_FAIL="1").returncode)
        self.assertTrue((self.base / "system/app/Tilepad.apk").exists())
        self.assertTrue((self.base / "system/app/TilepadTweaks.apk").exists())


if __name__ == "__main__":
    unittest.main()
