#!/usr/bin/env python3
"""Triage a Tilepad compatibility log against what the repo already supports.

usage: python3 -I tools/compat/triage.py <tilepad-compat.txt> .   (run from the repo root)

Prints the unit's identity, each logged hash with what (if anything) in the repo knows it,
whether each gate would pass, and the tweaks' own unavailable reasons. Reads only.
"""
import re
import sys
from pathlib import Path

log, repo = Path(sys.argv[1]), Path(sys.argv[2])
sections, current = {}, None
for line in log.read_text().splitlines():
    m = re.match(r"\[(\w+)\]", line)
    if m:
        current = sections.setdefault(m.group(1), [])
    elif current is not None and "=" in line:
        current.append(line.split("=", 1))
identity = dict(sections.get("identity", []))
files = sections.get("files", [])

# What the repo knows: Firmware.java releases, patch recipes, PreconditioningProfile constants.
known = {}
firmware = (repo / "tweaks-core/src/main/java/com/tilepad/tweakcore/Firmware.java").read_text()
fields = ["automotive", "automotiveService", "framework", "framework2", "evSettings"]
releases = []
for name, body in re.findall(r'new Release\("([^"]+)",(.*?)\)', firmware, re.S):
    hashes = re.findall(r'"([0-9a-f]{64})"', body)
    releases.append((name, dict(zip(fields, hashes))))
    for field, h in zip(fields, hashes):
        known.setdefault(h, []).append(f"Firmware {name} {field}")
recipes = {}
for path in sorted((repo / "tune-service/tweaks/patches").glob("*.properties")):
    props = dict(l.split("=", 1) for l in path.read_text().splitlines() if "=" in l and not l.startswith("#"))
    if "stockSha256" in props:
        recipes[path.name] = props
        known.setdefault(props["stockSha256"], []).append(f"recipe {path.name} stock")
        known.setdefault(props["apkSha256"], []).append(f"recipe {path.name} apk")
profile = (repo / "app/src/main/java/com/tilepad/PreconditioningProfile.java").read_text()
constants = dict(re.findall(r'static final String (\w+) = "([^"]+)";', profile))
for name, value in constants.items():
    if re.fullmatch(r"[0-9a-f]{64}", value):
        known.setdefault(value, []).append(f"Precondition {name}")

print("[identity]")
for key in ("ro.build.product", "ro.product.device", "ro.build.fingerprint", "ro.product.vehicleinfo"):
    print(f"  {key} = {identity.get(key)}")
climate = [identity.get(f"persist.sys.climate.{k}") for k in ("variant", "option1", "option2", "option3", "option4", "option5")]
print(f"  climate variant/options = {climate}   (IONIQ 5 and EV6: ['2', '41', '4', '47', '3', '1'])")

print("\n[files]")
hashes = {}
for key, value in files:
    hashes[key] = value
    # Layouts need no match: the track recipe names no file and checks the two lines on the car.
    state = "known" if value in known else "kl   " if key.endswith(".kl") else "NEW  "
    print(f"  {state} {key}  {value[:12]}  {'; '.join(known.get(value, []))}")

def logged(path):
    return hashes.get(path)

print("\n[gates]")
auto, svc = logged("/system/framework/automotive.odex"), logged("/system/framework/automotive-service.odex")
fw, fw2 = logged("/system/framework/framework.odex"), logged("/system/framework/framework2.odex")
ev = logged("/system/app/HKMC_TAppEV3.apk")
match = lambda test: [n for n, r in releases if test(r)] or "NO"
print(f"  vehicle (automotive + automotive-service): {match(lambda r: r['automotive'] == auto and r['automotiveService'] == svc)}")
print(f"  projection (framework + framework2):       {match(lambda r: r['framework'] == fw and r['framework2'] == fw2)}")
print(f"  Charge-Away (HKMC_TAppEV3.apk):            {match(lambda r: r['evSettings'] == ev)}")
for target in ("/system/priv-app/SystemUI.odex", "/system/app/RightScreenHome.odex"):
    # The log may also carry Tilepad's stock backup of a file it already patched.
    candidates = [v for k, v in files if k.startswith(target) or (target in k and "stock" in k)]
    hit = [n for n, p in recipes.items() if p["path"] == target and p["stockSha256"] in candidates]
    print(f"  recipe for {target}: {hit or 'NO'}")
device = identity.get("ro.product.device", "")
platform = (repo / "tweaks-core/src/main/java/com/tilepad/tweakcore/Platform.java").read_text()
prefixes = re.findall(r'Build\.DEVICE\.startsWith\("([^"]+)"\)', platform)
print(f"  dual-zone device prefix {prefixes}: {'yes' if any(device.startswith(p) for p in prefixes) else 'NO'}")
software = identity.get("ro.build.product")
pre = [n for n, v in constants.items() if v == software]
print(f"  Precondition software constant: {pre or 'NO'}; hashes known there: "
      f"{'yes' if auto in known and any('Precondition' in k for k in known[auto]) else 'NO'}")

print("\n[tweaks]")
for key, value in sections.get("tweaks", []):
    print(f"  {key}={value}")
