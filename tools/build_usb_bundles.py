#!/usr/bin/env python3
"""Build the NEMESIS package and USB install/update/uninstall bundles; never installs or connects to a car."""
import argparse
import configparser
from pathlib import Path
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE_ID = "tilepad"
ACTIONS = ("install", "update", "uninstall")

def pack(source, destination):
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(source).as_posix())

def require_signed(apk):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
    if "classes.dex" not in names:
        raise SystemExit(f"{apk} has no classes.dex")
    if not any(n.startswith("META-INF/") and n.upper().endswith((".RSA", ".DSA", ".EC")) for n in names):
        raise SystemExit(f"{apk} is not signed; build it with tools/build_apk.sh (it goes into /system/app)")

def check_descriptor(path):
    # NEMESIS 1.16 (descriptor format 1.1) refuses these at install time; fail the build instead.
    ini = configparser.ConfigParser(interpolation=None)
    ini.read(path, encoding="utf-8")
    meta = ini["nemesis"]
    dotted = re.compile(r"\d+(\.\d+)*")
    problems = []
    if meta.get("version") != "1.1":
        problems.append("version must be 1.1 (the descriptor format; Tilepad's own goes in package-version)")
    if meta.get("id") != PACKAGE_ID:
        problems.append(f"id must be {PACKAGE_ID}")
    if not dotted.fullmatch(meta.get("package-version", "")):
        problems.append("package-version must be dotted numbers")
    if not 0 < len(meta.get("short-desc", "")) <= 64:
        problems.append("short-desc must be 1-64 characters")
    if len(meta.get("detailed-desc", "")) > 256:
        problems.append("detailed-desc must be at most 256 characters")
    for section in ini.sections():
        for key in ("path", "preinstall", "postinstall", "preuninstall", "postuninstall"):
            value = ini[section].get(key)
            if value and (".." in value.split("/") or not (path.parent / value).is_file()):
                problems.append(f"[{section}] {key}={value} is not a file in the package")
    if problems:
        raise SystemExit(f"{path}:\n  " + "\n  ".join(problems))

def copy_lf(source, destination):
    # Car-side scripts run under KitKat mksh; never ship CRLF.
    destination.write_bytes(source.read_bytes().replace(b"\r\n", b"\n"))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "dist/Tilepad.apk",
                        help="Signed Tilepad APK (default: dist/Tilepad.apk from tools/build_apk.sh)")
    parser.add_argument("--tweaks-apk", type=Path, default=ROOT / "dist/Tweaks.apk")
    args = parser.parse_args()
    apk = args.apk.resolve()
    if not apk.is_file():
        raise SystemExit(f"Missing {apk}. Run tools/build_apk.sh first, or pass --apk.")
    require_signed(apk)
    tweaks_apk = args.tweaks_apk.resolve()
    if not tweaks_apk.is_file():
        raise SystemExit(f"Missing {tweaks_apk}. Build both APKs with tools/build_apk.sh.")
    require_signed(tweaks_apk)

    # Work only in this repository's generated build directory.
    stage = ROOT / "build/usb-bundles"
    if stage.exists():
        shutil.rmtree(stage)
    package = stage / "package"
    package.mkdir(parents=True)
    for path in sorted((ROOT / "tune-service").rglob("*")):
        if path.is_file():
            target = package / path.relative_to(ROOT / "tune-service")
            target.parent.mkdir(parents=True, exist_ok=True)
            copy_lf(path, target)
    (package / "app").mkdir()
    shutil.copy2(apk, package / "app/Tilepad.apk")
    shutil.copy2(tweaks_apk, package / "app/Tweaks.apk")
    check_descriptor(package / "NEMESIS.data")

    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    package_zip = dist / f"{PACKAGE_ID}.zip"
    pack(package, package_zip)

    for action in ACTIONS:
        usb = stage / action
        scripts = usb / "tilepad"
        scripts.mkdir(parents=True)
        copy_lf(ROOT / "tune-service/scripts/common.sh", scripts / "common.sh")
        copy_lf(ROOT / "usb/tilepad/lib.sh", scripts / "lib.sh")
        copy_lf(ROOT / f"usb/tilepad/{action}.sh", scripts / f"{action}.sh")
        if action == "uninstall":
            copy_lf(ROOT / "usb/tilepad/restore-tweaks.sh", scripts / "restore-tweaks.sh")
            copy_lf(ROOT / "tune-service/scripts/remove.sh", scripts / "remove.sh")
            recovery = scripts / "recovery"
            recovery.mkdir()
            shutil.copy2(apk, recovery / "Tilepad.apk")
            shutil.copytree(ROOT / "tune-service/tweaks/patches", recovery / "patches")
        if action != "uninstall":
            (usb / "packages").mkdir()
            shutil.copy2(package_zip, usb / f"packages/{PACKAGE_ID}.zip")
        (usb / "NEMESIS.data").write_bytes(
            f"[nemesis]\nversion=1.0\ntype=execute\npath=tilepad/{action}.sh\n".encode())
        out = dist / f"Tilepad-{action}-usb.zip"
        pack(usb, out)
        print(out)
    print(package_zip)

if __name__ == "__main__":
    main()
