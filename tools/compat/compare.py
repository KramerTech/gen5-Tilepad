#!/usr/bin/env python3
"""Compare two deodexed trees (deodex.sh output) the way a compatibility check needs.

usage: python3 -I tools/compat/compare.py <reference smali/> <new smali/> [--mask-names] [--show TARGET]

For each target (automotive, SystemUI, ...) prints how many .smali files differ raw, and which
still differ once .line numbers and resource IDs (0x7f......, 0x01......) are masked. It also
checks that every literal that differs raw is resource-ID shaped, so the mask can't hide a real
constant change. --mask-names also masks short obfuscated identifiers (a, bC, ...), for builds
whose obfuscation renamed members; review what's left by hand. --show TARGET prints the
normalized diff for that target.
"""
import difflib
import re
import sys
from pathlib import Path

args = [a for a in sys.argv[1:] if not a.startswith("--")]
ref, new = Path(args[0]), Path(args[1])
mask_names = "--mask-names" in sys.argv
show = sys.argv[sys.argv.index("--show") + 1] if "--show" in sys.argv else None
RES = re.compile(r"\b0x(?:7f|0?1)[0-9a-f]{6}\b")
LITERAL = re.compile(r"\b0x[0-9a-f]+\b")
NAME = re.compile(r"(?<=[/;>$ ])[A-Za-z]{1,2}(?=[;(:$ ]|$)")


def normalize(text):
    out = []
    for line in text.splitlines():
        if line.lstrip().startswith(".line "):
            continue
        line = RES.sub("RES", line)
        line = re.sub(r"RES\s+#.*$", "RES", line)
        if mask_names:
            line = NAME.sub("N", line)
        out.append(line)
    return out


def files(root):
    return {p.relative_to(root).as_posix(): p for p in root.rglob("*.smali")}


for target in sorted(p.name for p in ref.iterdir() if p.is_dir()):
    a, b = files(ref / target), files(new / target) if (new / target).is_dir() else {}
    only = sorted(set(a) ^ set(b))
    raw, real, suspicious = 0, [], set()
    for rel in sorted(set(a) & set(b)):
        x, y = a[rel].read_text(), b[rel].read_text()
        if x == y:
            continue
        raw += 1
        nx, ny = normalize(x), normalize(y)
        if nx != ny:
            real.append(rel)
            if show == target:
                sys.stdout.writelines(l + "\n" for l in difflib.unified_diff(nx, ny, "ref/" + rel, "new/" + rel, n=2, lineterm=""))
        for line in difflib.ndiff(x.splitlines(), y.splitlines()):
            if line[:2] in ("- ", "+ ") and ".line " not in line:
                suspicious.update(v for v in LITERAL.findall(line) if not RES.fullmatch(v))
    # Literals that differ but aren't resource IDs, ignoring those present on both sides anyway.
    print(f"{target:20} files={len(a):5} raw-diff={raw:4} code-diff={len(real):3} only-one-side={len(only)}")
    for rel in real[:15]:
        if re.search(r"/R\$\w+\.smali$", rel):
            # Generated resource tables: name what was added or removed, not the shifted IDs.
            fa, fb = (set(re.findall(r"\.field public static final (\w+):", f[rel].read_text())) for f in (a, b))
            print(f"    resources {rel}: added {sorted(fb - fa)} removed {sorted(fa - fb)}")
        else:
            print(f"    differs: {rel}")
    if len(real) > 15:
        print(f"    ... {len(real) - 15} more")
    for rel in only[:10]:
        print(f"    only in {'ref' if rel in a else 'new'}: {rel}")
    if suspicious and real:
        print(f"    non-resource literals on changed lines: {sorted(suspicious)[:20]}")
