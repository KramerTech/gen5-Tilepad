#!/usr/bin/env python3
"""Compile/run pure policy and transactional tests; optional real-capture patch verification."""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def main():
    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
    jars = []
    for group, artifact, version in (("junit", "junit", "4.13.2"), ("org.hamcrest", "hamcrest-core", "1.3")):
        candidates = list((cache / group / artifact / version).glob("*/*.jar"))
        if not candidates:
            raise SystemExit(f"Missing {artifact} in Gradle cache. Run Gradle unit tests once or set GRADLE_USER_HOME.")
        jars.append(str(candidates[0]))
    classes = ROOT / "build/tweaks-host-tests"
    classes.mkdir(parents=True, exist_ok=True)
    core = ROOT / "tweaks-core/src/main/java/com/tilepad/tweakcore"
    sources = [core / (n + ".java") for n in ("Config", "Files", "DstRules", "HeaterPolicy", "PatchManager", "ClimatePolicy", "Transaction")]
    pure_names = ("AdbSnapshot", "TweaksSnapshot", "ConnectionSnapshot", "TileSpec", "TileOrder", "TileSupport", "GridMath", "PagedGridMath", "SearchFilter", "PreconPresenter", "PreconditioningProfile", "PreconditioningSnapshot", "PreconditioningController", "VehiclePort")
    sources += [ROOT / "app/src/main/java/com/tilepad" / (n + ".java") for n in pure_names]
    sources += [ROOT / "app/src/main/java/com/tilepad/longstar" / (n + ".java") for n in ("Evdev", "HoldDetector")]
    tests = sorted((ROOT / "app/src/test/java").rglob("*Test.java")) + sorted((ROOT / "tweaks/src/test/java").rglob("*Test.java"))
    sources.append(ROOT / "tweaks/src/main/java/com/tilepad/tweaks/SettingsEdits.java")
    sources += tests
    classpath = os.pathsep.join(jars)
    subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-classpath", classpath, "-d", str(classes), *map(str, sources)], check=True, cwd=ROOT)
    names = []
    for test in tests:
        relative = str(test).split("/src/test/java/")[1]
        names.append(relative[:-5].replace("/", "."))
    subprocess.run(["java", "-cp", str(classes) + os.pathsep + classpath, "org.junit.runner.JUnitCore", *names], check=True, cwd=ROOT)
    subprocess.run([sys.executable, str(ROOT / "tools/tweaks/test_install.py")], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
