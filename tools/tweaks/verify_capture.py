#!/usr/bin/env python3
"""Verify production patches against an optional local capture; never contacts a device."""
import argparse
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
# Nav-only RightScreenHome.odex MD5 checked on the car; only the IONIQ 5 USA V015.012 capture has one.
NAV_MD5 = {"v015": "e8eac4e6a0f33dc2e4cca904298a8682"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("firmware", type=Path, help="Captured system directory")
    parser.add_argument("--release", default="v015", help="Which release the capture is (default v015, the IONIQ 5 capture; a compatibility log's system/ folder works if it has the APKs)")
    parser.add_argument("--baksmali", type=Path, default=Path.home() / ".local/share/smali/baksmali-2.5.2.jar")
    args = parser.parse_args()
    recipes = ROOT / "nemesis-package/tweaks/patches"
    with tempfile.TemporaryDirectory(prefix="tilepad-recipes-") as directory:
        generated = Path(directory)
        subprocess.run([sys.executable, str(ROOT / "tools/tweaks/firmware_patches.py"), str(args.firmware), args.release, "--out", str(generated)], check=True)
        # This capture reproduces its own release's recipes; other releases came from their logs.
        for path in generated.glob("*.properties"):
            if path.read_bytes() != (recipes / path.name).read_bytes():
                raise SystemExit("Non-reproducible recipe: " + path.name)
    classes = ROOT / "build/tweaks-host-tests"
    subprocess.run(["javac", "--release", "8", "-cp", str(classes), "-d", str(classes), str(ROOT / "tools/tweaks/CaptureCheck.java")], check=True)
    work = ROOT / "build/capture-patch-check"
    subprocess.run(["java", "-cp", str(classes), "CaptureCheck", str(args.firmware), str(work), str(recipes), NAV_MD5.get(args.release, "-")], check=True)
    if args.baksmali.is_file():
        checks = (("RightScreenHome", "Lcom/mobis/splitscreen/right/k/r;,Lcom/mobis/splitscreen/right/r/i;"),
                  ("SystemUI", "Lcom/android/systemui/consentscreen/D;"))
        for app, types in checks:
            subprocess.run(["java", "-jar", str(args.baksmali), "d", "-a", "18", "--allow-odex-opcodes", "--classes", types,
                            "--code-offsets", "-o", str(work / "disassembly" / app), str(work / "patched" / (app + ".odex"))], check=True)
        print("PASS: modified ODEX files independently disassembled; outputs in build/capture-patch-check/disassembly")


if __name__ == "__main__":
    main()
