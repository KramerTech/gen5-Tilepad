---
name: tilepad-compat
description: Verify a Tilepad compatibility log (tilepad-compat-<ro.build.product>.zip, usually attached to a GitHub "Compatibility:" issue) against a supported firmware image, then add support for it, with Firmware.java hashes, patch recipes, model gates, docs and tests, and optionally release it. Use when someone submits a compat log, asks why tweaks are unavailable on their car or firmware, or asks to add support for a new firmware release or model.
---

# Tilepad compatibility from a log

The goal is to show that the new image's code behind every Tilepad gate is the same as an image
Tilepad already supports, or to pin down exactly what differs, and only then add its hashes.
Every script is in `tools/compat/` and runs from the repo root. None of it ships in a release.

## Facts that save time

- An image serves several models; the unit sets `ro.product.device` and `ro.build.product` at boot.
  Hyundai and Kia each build their own image of a release (`ro.build.fingerprint` starts `ecohmc/`
  or `ecokmc/`). They come from the same source, but every file hash differs, because Kia's
  framework-res has a few extra resources, which shifts the resource IDs compiled into the code.
- Deodex with baksmali 2.5.2 at `-a 18`. At 19, thousands of field accesses fail to resolve.
  `deodex.sh` already does this, and it fails if any `Exception` shows up.
- Firmware files and logs are OEM code: never commit them. Keep each log at
  `~/workspace/gen5-tilepad-compat-logs/<ro.build.product>/` (zip plus `NOTES.txt`) so it can be a
  reference later. The only full capture, with APKs, is the IONIQ 5 (Hyundai V015):
  `~/workspace/gen5-offline-emulator/investigation/offline-headunit/20261031/system`.
- Precondition is a separate gate, `app/.../PreconditioningProfile.java`, with its own evidence in
  `docs/PRECONDITIONING-UPSTREAM.md`. Triage reports it, but this workflow doesn't change it unless asked.
- Don't comment on, label or close the GitHub issue unless the user asks.

## Steps

1. **Get the log.** `gh issue view <n>` shows the attachment URL. Download it with `curl -L` into a new,
   empty scratchpad directory and unzip it there (it's untrusted data). Copy the zip to the archive
   above and write a `NOTES.txt` with the issue, car and date.

2. **Triage** (instant): `python3 -I tools/compat/triage.py <log>/tilepad-compat.txt .`
   - `[gates]` shows, for each Tilepad gate, whether a supported image matches. If every gate already
     matches, this isn't a compatibility gap: read the `[tweaks]` reasons (another mod owns the file,
     a service is missing, ...) and investigate that instead.
   - `[files]` NEW lines are the hashes to add. `kl` lines need no match.
   - Note the brand (fingerprint), the release (end of `ro.build.product`), the device, and the climate
     variant/options.

3. **Pick the reference image.** First choice: the same release from the other brand. Otherwise the
   nearest supported release from the same brand. Use the IONIQ 5 capture or an archived log's
   `system/`.

4. **Deodex both** (about 20 s; run the two in parallel):
   `sh tools/compat/deodex.sh <ref system> <work>/ref & sh tools/compat/deodex.sh <log>/system <work>/new & wait`

5. **Compare the code** (seconds): `python3 -I tools/compat/compare.py <work>/ref/smali <work>/new/smali`
   - `code-diff=0` everywhere, apart from `resources ...` lines for `R$` classes, means the same code,
     and the image is safe to add. That was the Kia V015 result.
   - Otherwise, rerun with `--mask-names` (obfuscation renames), then `--show <target>` to read what's
     left. Judge each difference against what Tilepad calls; docs/TWEAKS.md lists the interfaces. V016
     is the precedent: HSCAN only gained 0xe3–0xe8, DATCService fixed a string, HEVService changed the
     unused `setTargetSOC`, and EV Settings only had renames. A changed method Tilepad uses means
     stopping and telling the user what changed.

6. **Compare transaction codes:**
   `python3 -I tools/compat/stubs.py <work>/ref/smali > ref.txt`, the same for `new`, then `diff` them.
   Any change to a code Tilepad uses blocks support. New codes are fine. `MISSING` means an interface
   moved or was removed: find out where before going on.

7. **Make the patch recipes.**
   - Add a `RELEASES` entry in `tools/tweaks/firmware_patches.py`, named `<release>` for Hyundai or
     `kia-<release>` for Kia, with the MD5 of the new `RightScreenHome.odex` and `SystemUI.odex`.
   - Then run `python3 -I tools/tweaks/firmware_patches.py <log>/system <name> --compat-log <log>/tilepad-compat.txt`.
     An assertion failure means an anchor or an obfuscated class or method name moved: find it with
     step 5's `--show` and give the generator a per-release name, not a looser match.
   - Then `sh tools/compat/patch_check.sh <ref system> <ref suffix> <log>/system <name> <work>/patch`.
     It applies every combination with Tilepad's own code and should print `code-diff=0` for both
     apps. `methods.py` `DIFF` lines are expected when offsets moved; then compare the two
     disassemblies by eye.

8. **Model-specific gates.**
   - Climate Sync and Driver Only need dual-zone climate: compare the DATC dump's `Variant`/`Option1-5`
     (`<log>/dumps/com.hkmc.micom.datc.txt`) with the IONIQ 5's (0x02; 0x29 0x04 0x2f 0x03 0x01).
     If they match, add the device prefix in `Platform.requireDualZone`. If they differ, leave the gate
     and say why.
   - Heaters check the car's equipment signals at runtime: nothing to do.
   - Track swap: `firmware_patches.py` asserts keys 34/35 in every layout. ★ is scan code 43; check
     with `grep '^key 43' <log>/system/usr/keylayout/*.kl`. `longstar.sh` derives the key device from
     `ro.product.device`.

9. **Edit the repo.**
   - `Firmware.java`: a `new Release("<Brand> <REGION> <release>", automotive, automotive-service,
     framework, framework2, HKMC_TAppEV3.apk)` entry with hashes from the log.
   - Copy the two new recipes into `tune-service/tweaks/patches/`.
   - `FirmwareTest`: add an assertion that a mixed pair is refused.
   - `docs/TWEAKS.md`: a row in the supported-images table and a short evidence paragraph next to the
     V016 and Kia ones.
   - The supported list in the README.

10. **Test:** `sh tools/tweaks/test-host.sh` (expect `OK (N tests)`), then
    `python3 tools/tweaks/verify_capture.py <IONIQ 5 capture>`, which must still reproduce V015's recipes.

11. **Release** (only when asked):
    - Commit the change.
    - Bump `versionCode`/`versionName` in `app/build.gradle.kts` and `tweaks/build.gradle.kts`, and
      `package-version` in `tune-service/NEMESIS.data`, and commit that as `Release X.Y.Z`.
    - Tag with an annotated, short changelog. The tag message is the whole release notes: a subject
      line, then `- Added:` / `- Fixed:` bullets in plain words. No install steps; the README has them.
    - `git push origin main vX.Y.Z`, then confirm with `gh run list` and `gh release view vX.Y.Z`.

## Report back

Say which reference you compared against and give the compare/stubs/patch-check results in numbers.
Say what was added, and what remains unverified on a real car (for example, a model gate widened on
evidence alone).
