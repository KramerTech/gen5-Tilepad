#!/usr/bin/env python3
"""Run a check across every archived compatibility log at once.

usage: python3 -I tools/compat/corpus.py <command> [args]     (run from the repo root)

  sync                  unpack and deodex each distinct firmware image once (cached; run after adding a log)
  gates                 the Tilepad gates for every log (triage.py's [gates] section)
  stubs                 transaction codes that differ between images, for every interface stubs.py knows
  method CLASS [NAME]   each image's copy of CLASS's methods (NAME: only names starting with it),
                        normalized as compare.py does; flags any method that differs between images
  grep REGEX            which images' deodexed code matches REGEX, and in which files

The archive is ~/workspace/gen5-tilepad-compat-logs (override with TILEPAD_COMPAT_LOGS): one folder
per ro.build.product holding issue-<n>.zip logs, plus references.txt, which lists full /system
captures as "<path>" lines. Logs from cars on the same image share one cache entry, under .cache/.
Firmware stays out of the repo.
"""
import hashlib
import io
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ARCHIVE = Path(os.environ.get("TILEPAD_COMPAT_LOGS", Path.home() / "workspace/gen5-tilepad-compat-logs"))
CACHE = ARCHIVE / ".cache"
# The files whose hashes identify an image (stock copies: a patched file maps back to its backup).
KEYS = ["framework/automotive.odex", "framework/automotive-service.odex", "framework/framework.odex",
        "framework/framework2.odex", "priv-app/SystemUI.odex", "app/RightScreenHome.odex"]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def logs():
    return sorted(ARCHIVE.glob("*/issue-*.zip"))


def references():
    listing = ARCHIVE / "references.txt"
    if not listing.is_file():
        return []
    return [Path(l.strip()).expanduser() for l in listing.read_text().splitlines() if l.strip() and not l.startswith("#")]


def log_files(z):
    """Stock-preferred bytes of KEYS, the build.prop text and the compat text of one log zip."""
    names = set(z.namelist())
    files = {}
    for key in KEYS:
        stock = "tilepad-stock/" + key.rsplit("/", 1)[1]
        files[key] = z.read(stock if stock in names else "system/" + key)
    return files, z.read("system/build.prop").decode(errors="replace"), z.read("tilepad-compat.txt").decode(errors="replace")


def image_name(build_prop, ids):
    props = dict(l.split("=", 1) for l in build_prop.splitlines() if "=" in l and not l.startswith("#"))
    product = props.get("ro.build.product", "unknown")  # the image's, e.g. ECOKMC.USA.0000.V015.012.260507
    return f"{product}-{ids[:8]}"


def images():
    """{image name: (kind, source, [log paths])} for every archived log and reference capture."""
    found = {}
    for path in logs():
        with zipfile.ZipFile(path) as z:
            files, build_prop, _ = log_files(z)
        name = image_name(build_prop, sha("".join(sha(files[k]) for k in KEYS).encode()))
        found.setdefault(name, ("log", path, []))[2].append(path)
    for system in references():
        files = {k: (system / k).read_bytes() for k in KEYS}
        name = image_name((system / "build.prop").read_text(errors="replace"), sha("".join(sha(files[k]) for k in KEYS).encode()))
        found[name] = ("capture", system, found.get(name, (None, None, []))[2])
    return found


def system_dir(name, kind, source):
    if kind == "capture":
        return source
    target = CACHE / name / "system"
    if not target.is_dir():
        with zipfile.ZipFile(source) as z:
            for entry in z.namelist():
                if entry.startswith("system/") and not entry.endswith("/"):
                    out = CACHE / name / entry
                    out.parent.mkdir(parents=True, exist_ok=True)
                    out.write_bytes(z.read(entry))
            # Put Tilepad's stock backups back over files it had patched.
            for entry in z.namelist():
                if entry.startswith("tilepad-stock/") and not entry.endswith("/"):
                    base = entry.rsplit("/", 1)[1]
                    for hit in target.rglob(base):
                        hit.write_bytes(z.read(entry))
    return target


def smali(name):
    return CACHE / name / "smali"


def sync():
    for name, (kind, source, members) in sorted(images().items()):
        if not (smali(name)).is_dir():
            system = system_dir(name, kind, source)
            print(f"deodexing {name} ...", flush=True)
            subprocess.run(["sh", str(ROOT / "tools/compat/deodex.sh"), str(system), str(CACHE / name)], check=True,
                           stdout=subprocess.DEVNULL)
        sources = [p.relative_to(ARCHIVE).as_posix() for p in members] or [str(source)]
        print(f"{name}  ({kind})  {', '.join(sources)}")


def gates():
    for path in logs():
        with zipfile.ZipFile(path) as z:
            text = z.read("tilepad-compat.txt")
        scratch = CACHE / "triage.txt"
        CACHE.mkdir(parents=True, exist_ok=True)
        scratch.write_bytes(text)
        out = subprocess.run([sys.executable, "-I", str(ROOT / "tools/compat/triage.py"), str(scratch), str(ROOT)],
                             check=True, capture_output=True, text=True).stdout
        section = out[out.index("[gates]") + 8:out.index("[tweaks]")].rstrip()
        print(f"{path.relative_to(ARCHIVE)}\n{section}\n")


def ready():
    names = sorted(n for n in images() if smali(n).is_dir())
    if not names:
        sys.exit("No deodexed images yet: run `corpus.py sync` first.")
    return names


def stubs():
    tables = {}
    for name in ready():
        out = subprocess.run([sys.executable, "-I", str(ROOT / "tools/compat/stubs.py"), str(smali(name))],
                             check=True, capture_output=True, text=True).stdout
        tables[name] = dict(l.rsplit(" = ", 1) if " = " in l else (l, "MISSING") for l in out.splitlines())
    keys = sorted(set().union(*tables.values()))
    differ = [k for k in keys if len({t.get(k, "absent") for t in tables.values()}) > 1]
    print(f"{len(keys)} transaction codes across {len(tables)} images; {len(differ)} differ")
    for k in differ:
        print(f"  {k}: " + ", ".join(f"{n}={t.get(k, 'absent')}" for n, t in tables.items()))


RES = re.compile(r"\b0x(?:7f|0?1)[0-9a-f]{6}\b")


def normalize(lines):
    return [re.sub(r"RES\s+#.*$", "RES", RES.sub("RES", l)) for l in lines if not l.lstrip().startswith(".line ")]


def methods(path, prefix):
    found, current, body = {}, None, []
    for line in path.read_text().splitlines():
        if line.startswith(".method "):
            current, body = line.split()[-1], []
        elif line.startswith(".end method") and current:
            if current.startswith(prefix):
                found[current] = sha("\n".join(normalize(body)).encode())[:12]
            current = None
        elif current:
            body.append(line)
    return found


def method(cls, prefix=""):
    rel = cls.strip("L;") + ".smali"
    table = {}
    for name in ready():
        hits = list(smali(name).glob("*/" + rel))
        table[name] = methods(hits[0], prefix) if hits else None
    present = [t for t in table.values() if t is not None]
    for name, t in table.items():
        if t is None:
            print(f"{name}: class missing")
    for m in sorted(set().union(*present)) if present else []:
        values = {n: (t or {}).get(m, "absent") for n, t in table.items() if t is not None}
        state = "same" if len(set(values.values())) == 1 else "DIFF"
        print(f"{state} {m}" + ("" if state == "same" else "\n    " + "\n    ".join(f"{n}: {v}" for n, v in values.items())))


def grep(pattern):
    regex = re.compile(pattern)
    for name in ready():
        hits = [p for p in smali(name).rglob("*.smali") if regex.search(p.read_text())]
        print(f"{name}: {len(hits)} files")
        for p in hits[:5]:
            print(f"    {p.relative_to(smali(name))}")


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in ("sync", "gates", "stubs", "method", "grep"):
        sys.exit(__doc__)
    command, args = sys.argv[1], sys.argv[2:]
    {"sync": sync, "gates": gates, "stubs": stubs, "method": method, "grep": grep}[command](*args)
