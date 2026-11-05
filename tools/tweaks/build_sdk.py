#!/usr/bin/env python3
"""Direct SDK fallback for restricted hosts where Gradle cannot open lock sockets.

Compiles the same release sources against API 19 and emits unsigned APKs in the
normal locations. Does NOT replace lint; CI still runs the Gradle/lint pipeline.
"""
import argparse
import os
from pathlib import Path
import subprocess
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def run(*args):
    subprocess.run([str(x) for x in args], check=True, cwd=ROOT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api19", type=Path, default=Path(os.environ.get("TILEPAD_API19_JAR", str(Path.home() / ".cache/cabinwright-tools/android-19.jar"))))
    parser.add_argument("--debug", action="store_true", help="Compile emulator preview sources; never used for USB releases")
    args = parser.parse_args()
    if not args.api19.is_file():
        parser.error("Provide --api19 or TILEPAD_API19_JAR pointing to Android API 19 android.jar")
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))
    bt = sdk / "build-tools" / os.environ.get("BUILD_TOOLS_VERSION", "36.0.0")
    link_jar = sdk / "platforms/android-36/android.jar"
    ns = "{http://schemas.android.com/apk/res/android}"
    ET.register_namespace("android", ns[1:-1])
    for module, package in (("app", "com.tilepad"), ("tweaks", "com.tilepad.tweaks")):
        variant = "debug" if args.debug else "release"
        work = ROOT / ("build/sdk-debug" if args.debug else "build/sdk-only") / module
        if work.exists():
            shutil.rmtree(work)
        classes, generated, dex = (work / s for s in ("classes", "generated", "dex"))
        for path in (classes, generated, dex):
            path.mkdir(parents=True, exist_ok=True)
        manifest = ET.parse(ROOT / module / "src/main/AndroidManifest.xml")
        m = manifest.getroot()
        m.set("package", package)
        gradle = (ROOT / module / "build.gradle.kts").read_text()
        m.set(ns + "versionCode", re.search(r'versionCode\s*=\s*(\d+)', gradle)[1])
        m.set(ns + "versionName", re.search(r'versionName\s*=\s*"([^"]+)"', gradle)[1])
        if args.debug:
            m.find("application").set(ns + "debuggable", "true")
        uses = ET.Element("uses-sdk", {ns + "minSdkVersion": "19", ns + "targetSdkVersion": "19"})
        m.insert(0, uses)
        manifest.write(work / "AndroidManifest.xml", encoding="utf-8", xml_declaration=True)
        resource_apk = work / "resources.apk"
        command = [bt / "aapt", "package", "-f", "-M", work / "AndroidManifest.xml", "-S", ROOT / module / "src/main/res",
                   "-I", link_jar, "-F", resource_apk, "-J", generated]
        assets = ROOT / module / "src/main/assets"
        if assets.is_dir():
            command += ["-A", assets]
        run(*command)
        sources = list((ROOT / module / "src/main/java").rglob("*.java"))
        sources += list((ROOT / module / ("src/" + variant + "/java")).rglob("*.java"))
        sources += list((ROOT / "tweaks-core/src/main/java").rglob("*.java"))
        sources += list(generated.rglob("*.java"))
        run("javac", "--release", "8", "-encoding", "UTF-8", "-classpath", args.api19, "-d", classes, *sources)
        jar = work / "classes.jar"
        run("jar", "cf", jar, "-C", classes, ".")
        run(bt / "d8", "--min-api", "19", "--lib", args.api19, "--output", dex, jar)
        if (dex / "classes.dex").read_bytes()[:8] != b"dex\n035\0":
            raise SystemExit("Wrong DEX version for KitKat")
        out = ROOT / module / "build/outputs/apk" / variant / f"{module}-{variant}-unsigned.apk"
        out.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(resource_apk) as src, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
            for name in src.namelist():
                dst.writestr(name, src.read(name))
            dst.write(dex / "classes.dex", "classes.dex")
        print(out.relative_to(ROOT))


if __name__ == "__main__":
    main()
