#!/usr/bin/env python3
"""Whether two images' ODEX files are the same file apart from when their source was built.

usage: python3 -I tools/compat/odex_same.py <reference system/> <new system/> [name.odex ...]
       python3 -I tools/compat/odex_same.py <a.odex> <b.odex>

For each ODEX both have (framework/, app/, priv-app/; or only the names given), prints one of:
  identical          the same bytes
  build-time only    the same embedded DEX, optimized data and dependency list (each boot class
                     path entry's SHA-1); only the source jar's recorded modification time and the
                     ODEX header checksum over it differ, and each file's checksum is valid
  DIFFERS            and which regions do: header, dex, deps, opt
A build-time-only pair runs the same code against the same dependencies, so a hash pinned for
one file (a Precondition profile) can be extended to the other with that evidence. Reads only.
"""
import struct
import sys
import zlib
from pathlib import Path


def layout(data):
    if data[:8] != b"dey\n036\0":
        raise ValueError("not an ODEX 036 file")
    dex_off, dex_len, deps_off, deps_len, opt_off, opt_len, _flags, checksum = struct.unpack_from("<8I", data, 8)
    return {"header": (0, 40), "dex": (dex_off, dex_off + dex_len), "deps": (deps_off, deps_off + deps_len),
            "opt": (opt_off, opt_off + opt_len)}, checksum


def checksum_ok(data, regions, checksum):
    # Dalvik's ODEX checksum is Adler-32 over the dependency table through the end of the optimized data.
    return zlib.adler32(data[regions["deps"][0]:regions["opt"][1]]) & 0xffffffff == checksum


def compare(a, b):
    if a == b:
        return "identical"
    ra, ca = layout(a)
    rb, cb = layout(b)
    if ra != rb or len(a) != len(b):
        return "DIFFERS: layout"
    # Mask the two fields a rebuild of identical sources changes: the header checksum (36..40)
    # and the dependency table's first word, the source jar's modification time.
    deps = ra["deps"][0]
    masked = lambda d: d[:36] + d[40:deps] + d[deps + 4:]
    if masked(a) == masked(b):
        if checksum_ok(a, ra, ca) and checksum_ok(b, rb, cb):
            when = lambda d: struct.unpack_from("<I", d, deps)[0]
            return f"build-time only (source mtime {when(a)} vs {when(b)})"
        return "DIFFERS: header checksum invalid"
    differing = [name for name, (s, e) in ra.items() if a[s:e] != b[s:e]]
    return "DIFFERS: " + ", ".join(differing or ["padding"])


def main(args):
    first, second = Path(args[0]), Path(args[1])
    if first.is_file():
        print(f"{first.name}: {compare(first.read_bytes(), second.read_bytes())}")
        return
    wanted = set(args[2:])
    for folder in ("framework", "app", "priv-app"):
        for ref in sorted((first / folder).glob("*.odex")):
            new = second / folder / ref.name
            if new.is_file() and (not wanted or ref.name in wanted):
                print(f"{folder}/{ref.name}: {compare(ref.read_bytes(), new.read_bytes())}")


if __name__ == "__main__":
    main(sys.argv[1:])
