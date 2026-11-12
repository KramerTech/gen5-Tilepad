"""Patched methods: same code, header included, at the same offset in both images?

usage: python3 -I tools/compat/methods.py tools/tweaks/firmware_patches.py <reference system/> <new system/>
Method names are the ones firmware_patches.py patches; update both together. DIFF is not fatal
by itself (offsets move between releases), but then read the two disassemblies side by side."""
import sys, struct, importlib.util
spec = importlib.util.spec_from_file_location("fp", sys.argv[1]); fp = importlib.util.module_from_spec(spec); spec.loader.exec_module(fp)
a, b = sys.argv[2], sys.argv[3]
checks = {"app/RightScreenHome.odex": [("Lcom/mobis/splitscreen/right/k/r;", "f", "(ZZ)Z"), ("Lcom/mobis/splitscreen/right/k/r;", "canShowSSOsd", "(ZZZ)Z"),
                                       ("Lcom/mobis/splitscreen/right/r/i;", "d", "(IIZ)V"), ("Lcom/mobis/splitscreen/right/r/i;", "handleShow", "()V")],
          "priv-app/SystemUI.odex": [("Lcom/android/systemui/consentscreen/D;", "dc", "()V"), ("Lcom/android/systemui/consentscreen/D;", "start", "()V"),
                                     ("Lcom/android/systemui/consentscreen/D;", "<init>", None)]}
for path, methods in checks.items():
    x, y = open(f"{a}/{path}", "rb").read(), open(f"{b}/{path}", "rb").read()
    dx, dy = fp.Dex(x), fp.Dex(y)
    assert dx.methods == dy.methods, "method tables differ"
    for owner, name, proto in methods:
        keys = [k for k in dx.code if k[0] == owner and k[1] == name and (proto is None or k[2] == proto)]
        for k in keys:
            ox, oy = dx.code[k], dy.code[k]
            n = struct.unpack_from("<I", x, ox - 4)[0] * 2   # insns_size precedes insns
            print("same" if (ox == oy and x[ox - 16:ox + n] == y[oy - 16:oy + n]) else "DIFF", path, k, hex(ox), n)


    print(path, "differing bytes in the whole file:", sum(1 for i, j in zip(x, y) if i != j))
