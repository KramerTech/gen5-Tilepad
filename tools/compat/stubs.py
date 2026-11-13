#!/usr/bin/env python3
"""Transaction tables of the binder interfaces Tilepad calls, for diffing two firmwares.

usage: python3 -I tools/compat/stubs.py <smali/> > stubs.txt     (then: diff ref-stubs.txt new-stubs.txt)

Prints "Interface TRANSACTION_name = code" for every Stub found under any target, so a renumbered
or removed method shows up even when the rest of a file changed. Covers the interfaces listed in
docs/TWEAKS.md; add one to INTERFACES when Tilepad starts calling it.
"""
import re
import sys
from pathlib import Path

INTERFACES = [
    "com/hkmc/usm/IUSMService", "com/hkmc/vr/IVRService", "com/hkmc/datc/IDATCService",
    "com/hkmc/datc/IDATCListener", "com/hkmc/bcm/IBCMService",
    "com/hkmc/telematics/hscan/tm_interface/ITM_HSCANService", "com/hkmc/time/ITimePolicyService",
    "com/hkmc/hev/IHEVService", "com/hkmc/hev/IHEVListener", "com/hkmc/gpm/IGpmInterfaces",
    "com/hkmc/gpm/IGpmListener", "android/dipo/IDiPOManager", "com/hkmc/cluster/IClusterService",
    "com/hkmc/cluster/INavigationClusterListener", "com/hkmc/split/manager/ISplitScreenInterfaces",
]
root = Path(sys.argv[1])
for iface in INTERFACES:
    stubs = sorted(root.glob(f"*/{iface}$Stub.smali"))
    if not stubs:
        print(f"{iface} MISSING")
        continue
    text = stubs[0].read_text()
    for name, code in sorted(re.findall(r"\.field static final (TRANSACTION_\w+):I = (0x[0-9a-f]+)", text), key=lambda t: int(t[1], 16)):
        print(f"{iface.rsplit('/', 1)[1]} {name} = {code}")
