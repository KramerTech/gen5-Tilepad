---
name: tilepad-compat
description: Verify a Tilepad compatibility log (tilepad-compat-<ro.build.product>.zip, usually attached to a GitHub "Compatibility:" issue) against the archive of every firmware image seen so far, then add support for it, with Firmware.java hashes, patch recipes, model gates, docs and tests, and optionally release it. Also use it to check a new feature or OEM call against every archived image before shipping it. Use when someone submits a compat log, asks why tweaks are unavailable on their car or firmware, asks to add support for a new firmware release or model, or adds a feature that calls OEM code.
---

# Tilepad compatibility from a log

The goal is to show that the new image's code behind every Tilepad gate is the same as an image
Tilepad already supports, or to pin down exactly what differs, and only then add its hashes.
Every script is in `tools/compat/` and runs from the repo root. None of it ships in a release.

## The archive

`~/workspace/gen5-tilepad-compat-logs/` holds every log received, one folder per
`ro.build.product`, each with `issue-<n>.zip` files and a `NOTES.txt`. Its `references.txt` lists
full `/system` captures. Today that's the user's own IONIQ 5 (Hyundai V015), the firmware the
offline emulator runs: `~/workspace/gen5-offline-emulator/investigation/offline-headunit/20261031/system`.

`python3 -I tools/compat/corpus.py sync` groups logs by firmware image (several cars often share
one) and deodexes each image once into `.cache/<image>/{system,smali}`. It's cached, so later runs
are instant. After a sync, every command below runs against all images at once. Firmware and logs
are OEM code: never commit them.

## Facts that save time

- An image serves several models; the unit sets `ro.product.device` and `ro.build.product` at boot.
  Hyundai and Kia each build their own image of a release (`build.prop` names it `ECOHMC.…` or
  `ECOKMC.…`). They come from the same source, but every file hash differs, because Kia's
  framework-res has a few extra resources, which shifts the resource IDs compiled into the code.
- Deodex with baksmali 2.5.2 at `-a 18`. At 19, thousands of field accesses fail to resolve.
  `deodex.sh` already does this, and it fails if any `Exception` shows up.
- Precondition is a separate gate, `app/.../PreconditioningProfile.java`, with its own evidence in
  `docs/PRECONDITIONING-UPSTREAM.md`. Triage reports it, but this workflow doesn't change it unless asked.
- Don't comment on, label or close the GitHub issue unless the user asks.

## Adding a log

1. **Get it into the archive.** `gh issue view <n>` shows the attachment URL. Download it with
   `curl -L` to `<archive>/<ro.build.product>/issue-<n>.zip`, the product being the one in its file
   name. Add the issue, car and date to that folder's `NOTES.txt`. Then
   `python3 -I tools/compat/corpus.py sync`: it prints each image with its logs, so you can see
   whether this car shares an image with one already there. Shared and already supported means
   there's no new firmware to add.

2. **Triage:** `python3 -I tools/compat/corpus.py gates` (every log), or `triage.py` on one log's
   `tilepad-compat.txt` for the full report.
   - If every gate already matches, this isn't a compatibility gap: read the `[tweaks]` reasons
     (another mod owns the file, a service is missing, ...) and investigate that instead.
   - `[files]` NEW lines are the hashes to add. `kl` lines need no match.
   - Note the brand, the release (end of `ro.build.product`), the device, and the climate
     variant/options.

3. **Compare the code with the closest supported image:** the same release from the other brand,
   or else the nearest supported release from the same brand.
   `python3 -I tools/compat/compare.py <cache>/<ref image>/smali <cache>/<new image>/smali` (seconds).
   - `code-diff=0` everywhere, apart from `resources ...` lines for `R$` classes, means the same code,
     and the image is safe to add. That was the Kia V015 result.
   - Otherwise, rerun with `--mask-names` (obfuscation renames), then `--show <target>` to read what's
     left. Judge each difference against what Tilepad calls; docs/TWEAKS.md lists the interfaces. V016
     is the precedent: HSCAN only gained 0xe3–0xe8, DATCService fixed a string, HEVService changed the
     unused `setTargetSOC`, and EV Settings only had renames. A changed method Tilepad uses means
     stopping and telling the user what changed.

4. **Compare transaction codes:** `python3 -I tools/compat/corpus.py stubs` lists every code that
   differs between any two images. New codes are fine. A code Tilepad uses that now names a different
   method blocks support, unless the same method just has a new number. Then record each release's
   number in `Firmware.java`, as V012's IUSMService heater codes are (`Firmware.Usm`). V012 renumbered
   all of IUSMService, so check by name: for each code Tilepad calls, the method name on every
   image, and the service implementation's method body (`corpus.py method`).

5. **Make the patch recipes.** For a log, `<new system>` is `<cache>/<new image>/system`.
   - Add a `RELEASES` entry in `tools/tweaks/firmware_patches.py`, named `<release>` for Hyundai or
     `kia-<release>` for Kia, with the MD5 of the new `RightScreenHome.odex` and `SystemUI.odex`.
   - Then run `python3 -I tools/tweaks/firmware_patches.py <new system> <name> --compat-log <its tilepad-compat.txt>`.
     An assertion failure means an anchor or an obfuscated class or method name moved: find it with
     step 3's `--show` and give the generator a per-release entry in `NAMES` (names, nav branch
     offsets and bytes, the consent call and its `this` register), not a looser match. V012 is the
     example. Find each method by its unobfuscated neighbours (`canShowSSOsd`, `handleShow`), confirm
     it's the same code with names masked, then read the patched instructions' bytes from the odex.
   - Then `sh tools/compat/patch_check.sh <ref system> <ref suffix> <new system> <name> <work>/patch`.
     It applies every combination with Tilepad's own code and should print `code-diff=0` for both
     apps. `methods.py` `DIFF` lines are expected when offsets moved; then compare the two
     disassemblies by eye. For a release in `NAMES` it compares with names masked and skips
     methods.py: compare each patched method with V015's (names, registers and field offsets masked)
     and check every difference was already there before patching.

6. **Model-specific gates.**
   - Climate Sync and Driver Only need dual-zone climate: compare the DATC dump's `Variant`/`Option1-5`
     (`dumps/com.hkmc.micom.datc.txt` in the zip) with the IONIQ 5's (0x02; 0x29 0x04 0x2f 0x03 0x01).
     If they match, add the device prefix in `Platform.requireDualZone`. If they differ, leave the gate
     and say why.
   - Heaters check the car's equipment signals at runtime: nothing to do.
   - Track swap: `firmware_patches.py` asserts keys 34/35 in every layout. ★ is scan code 43; check
     with `grep '^key 43' <new system>/usr/keylayout/*.kl`. `longstar.sh` derives the key device from
     `ro.product.device`.

7. **Edit the repo.**
   - `Firmware.java`: a `new Release("<Brand> <REGION> <release>", automotive, automotive-service,
     framework, framework2, HKMC_TAppEV3.apk)` entry with hashes from the log.
   - Copy the two new recipes into `nemesis-package/tweaks/patches/`.
   - `FirmwareTest`: add an assertion that a mixed pair is refused.
   - `docs/TWEAKS.md`: a row in the supported-images table and a short evidence paragraph next to the
     V016 and Kia ones.
   - The firmware list under "Supported cars" in the README.

8. **Test:** `sh tools/tweaks/test-host.sh` (expect `OK (N tests)`), then
   `python3 tools/tweaks/verify_capture.py <IONIQ 5 capture>`, which must still reproduce V015's recipes.
   `corpus.py gates` should now show every gate matching for the new log.

9. **Release** (only when asked):
   - Commit the change.
   - Bump `versionCode`/`versionName` in `app/build.gradle.kts` and `tweaks/build.gradle.kts`, and
     `package-version` in `nemesis-package/NEMESIS.data`, and commit that as `Release X.Y.Z`.
   - Tag with an annotated, short changelog. The tag message is the whole release notes: a subject
     line, then `- Added:` / `- Fixed:` bullets in plain words. No install steps; the README has them.
   - `git push origin main vX.Y.Z`, then confirm with `gh run list` and `gh release view vX.Y.Z`.

## Checking a new feature against every image

Before shipping anything that calls OEM code (a new transaction, service method, or patched method),
check it everywhere at once:

- `corpus.py method 'Lcom/hkmc/micom/DATCService;' setFront` compares each image's copy of the
  methods whose names start with that prefix, normalized. `same` means identical on every image;
  `DIFF` lists each image's hash and needs a closer look; `class missing` means an image lacks it.
- `corpus.py stubs` after adding the interface to `INTERFACES` in `stubs.py`: the transaction code
  must exist, with the same number, on every image the feature is gated to.
- `corpus.py grep '<regex>'` finds a string, field or descriptor in every image.
- For behavior at runtime, the V015 emulator (AVD `emulator-5580`, started with
  `bash tools/emulator-theme/launch.sh` in `~/workspace/gen5-offline-emulator`) runs the captured
  identity with stubbed vehicle services: good for UI and in-process logic, not for real vehicle
  replies. Tweaks' preview is `--ez design_preview true` (docs/TWEAKS.md).

If a feature only holds on some images, gate it on those images' hashes in `Firmware.java`; don't
widen a gate.

## Report back

Say which images you compared and give the compare, stubs and patch-check results as numbers.
Say what was added and the evidence behind any gate you widened.
