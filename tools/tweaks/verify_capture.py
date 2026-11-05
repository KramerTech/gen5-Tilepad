#!/usr/bin/env python3
"""Verify production patches against an optional local capture; never contacts a device."""
import argparse
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("firmware", type=Path, help="Captured system directory")
    parser.add_argument("--baksmali", type=Path, default=Path.home() / ".local/share/smali/baksmali-2.5.2.jar")
    args = parser.parse_args()
    recipes = ROOT / "tune-service/tweaks/patches"
    with tempfile.TemporaryDirectory(prefix="tilepad-recipes-") as directory:
        generated = Path(directory)
        subprocess.run([sys.executable, str(ROOT / "tools/tweaks/firmware_patches.py"), str(args.firmware), "--out", str(generated)], check=True)
        for path in recipes.glob("*.properties"):
            if path.read_bytes() != (generated / path.name).read_bytes():
                raise SystemExit("Non-reproducible recipe: " + path.name)
    classes = ROOT / "build/tweaks-host-tests"
    subprocess.run(["javac", "--release", "8", "-cp", str(classes), "-d", str(classes), str(ROOT / "tools/tweaks/CaptureCheck.java")], check=True)
    work = ROOT / "build/capture-patch-check"
    subprocess.run(["java", "-cp", str(classes), "CaptureCheck", str(args.firmware), str(work), str(recipes)], check=True)
    if args.baksmali.is_file():
        checks = (("RightScreenHome", "Lcom/mobis/splitscreen/right/k/r;,Lcom/mobis/splitscreen/right/r/i;"),
                  ("SystemUI", "Lcom/android/systemui/consentscreen/D;"))
        for app, types in checks:
            subprocess.run(["java", "-jar", str(args.baksmali), "d", "-a", "18", "--allow-odex-opcodes", "--classes", types,
                            "--code-offsets", "-o", str(work / "disassembly" / app), str(work / "patched" / (app + ".odex"))], check=True)
        print("PASS: modified ODEX files independently disassembled; outputs in build/capture-patch-check/disassembly")


if __name__ == "__main__":
    main()
