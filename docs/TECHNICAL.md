# Tilepad technical notes

How Tilepad works under the hood, and how to build and release it. For installing and using it,
see the [README](../README.md). The bundled tweaks are described in detail in [TWEAKS.md](TWEAKS.md).

## What gets installed

The NEMESIS package lives in `/data/nemesis/apps/tilepad/`. The installer places
`/system/app/Tilepad.apk` and `/system/app/TilepadTweaks.apk`. NEMESIS runs one service,
`tilepad/longstar`: the steering-wheel ★ hold reader, which also hosts the listeners of any enabled
tweak.

The ★ reader reads the head unit's key device, `daudio_<ro.product.device>`, and never grabs it
or injects events. Hyundai runs the action Button settings assign to ★ (Home, for example) on the
release, after a hold too, so it would cover Tilepad. When a hold qualifies (800 ms), the reader
reads that action from the mode service (`com.hkmc.micom.mode`, `IModeManager.getKeyAction`). The
key type is 5 for the steering-wheel ★ and 3 for a car whose held key is its custom key instead
(`SHORTCUT_CUSTOM`, as on the Sonata; `KEY_CODE=auto` picks it); below, "★" means that key, and
each key has its own None action (`SWRC_CUSTOM_SHORTCUT`, `CUSTOM_SHORTCUT`):

- With ★ set to None there's nothing to skip: Tilepad opens at once and nothing is blocked. None is
  the only setting with which the car sends its long-press broadcast (`SWRC_CUSTOM_SHORTCUT`), and
  `StarHoldReceiver` already keeps that from opening Button settings.
- With an action, the reader asks the car to skip it (`disableKeyAction(type, [action, None's
  action])`), and Tilepad opens at once. On the release `ModeKeyDispatcher` logs "swrc Custom Key
  Action is blocked by app" (or "Custom Key Action …" for type 3) and skips the action. The block
  is lifted (`enableKeyAction(type)`) 500 ms after the release, at the next press, or 2 minutes
  after it was made, whichever comes first.
- If the action can't be read or the block doesn't take, Tilepad opens 250 ms after the release
  instead, just after the car's own action, so it still ends up on top.

These calls go through `Platform`'s checks, like the tweaks' calls: the vehicle files must be a
supported release, whose `IModeManager` codes they use (getKeyAction, disableKeyAction,
enableKeyAction are 0x2b, 0x2c, 0x2d, and one lower on V012: `Firmware.Mode`), the service must have
the `com.hkmc.mode.IModeManager` descriptor, and the reply must be as expected. A failed check counts as a block that can't be made.
A short press is never touched.

While a block is in place, `/data/nemesis/backup/tilepad/star.blocked` exists, holding
`<enableKeyAction code> <key type>`. A service that starts and finds it (it was stopped in the
middle of a hold) lifts the block; without the file, it lifts nothing at start. `remove.sh` does the
same with the recorded code and type (`service call com.hkmc.micom.mode 45 i32 5` on most cars) on an
update or uninstall that stops the service in the middle of a hold, and calls nothing if the file
can't be read. The mode service keeps one list
of blocked ★ actions, which can't be read, and `enableKeyAction` clears all of it: lifting
Tilepad's block also lifts one that a car app made during the hold.

Static tweaks, when explicitly enabled, change exact supported system files and keep verified stock
backups; disabling them restores the originals. Uninstall restores owned tweaks before removing the
APKs, then deletes Tilepad's saved tweak settings (including the Charge-Away Home location).
Turning on the Wi-Fi client is a separate confirmed action (below).

## Feature detection

Hyundai's firmware declares each car's options as system features. The Wi-Fi tile needs
`com.hkmc.software.wifi.module` and the Precondition tile needs `com.hkmc.hardware.ev`. Without
them those tiles don't appear, and Tilepad never contacts the vehicle service.

Preconditioning supports ten exact upstream firmware profiles, plus one of Tilepad's, across US
and Korean IONIQ 5 and EV6 units and the US IONIQ 6. On other firmware it can't start
preconditioning or send commands: the
Android version, device, software identity and both OEM framework hashes must match a supported
profile. The backend is Launchpad's, unchanged: firmware-hash gate, ownership journal,
cancel-on-recovery, and a foreground service that keeps the request alive after the app closes.
"Active" only ever comes from the car's own indicator, never from a timer. Compatibility and safety
notes are in Launchpad's
[COMPATIBILITY.md](https://github.com/paytondevs/gen5w-preconditioning/blob/main/COMPATIBILITY.md);
the pinned profiles, imported fixes and validation are in
[the upstream integration notes](PRECONDITIONING-UPSTREAM.md).

## Wi-Fi client

Stock US firmware, and wireless CarPlay/Android Auto mods installed without their Wi-Fi client
option (HMG Leafia Plus's `LEAFIA-WIFI-CLIENT`), use the radio only for the projection hotspot.
Turning the client on does what Leafia's option does: it adds
`/system/etc/permissions/android.hardware.wifi.xml` (Hyundai's Wi-Fi service and settings app read
it as "client supported") and sets `persist.wifi.state=3` (client + wireless). It doesn't write
`build.prop`; the persistent property survives reboots on its own. Android reads features at boot,
hence the reboot. The root side is `nemesis-package/bin/wifi-client.sh`.

- It's idempotent and never touches a file that's already there, so it can't fight Leafia. If
  Leafia is installed later with its client option, it writes the same file. If Leafia was
  installed first without it, Leafia's uninstall restores its snapshot and removes the client
  again, and the tile offers it again.
- Uninstalling Tilepad removes the file, if it's still exactly what Tilepad wrote, and puts
  `persist.wifi.state` back. It leaves the client alone while HMG Leafia Plus is installed, since
  Leafia's option uses the same file.

## ADB over Wi-Fi

**On** sets the firmware's own switch for ADB over TCP (`persist.service.adb.tcp.enable`), so adbd
listens on port 5555, now and after every reboot. While it's on, Tilepad also turns Wi-Fi on at
every boot (the head unit boots with the CarPlay hotspot up instead). **Off** clears the switch and
stops adbd, and leaves Wi-Fi as it is. This head unit's adbd doesn't check keys over TCP, and it
serves one transport at a time.

## USB drive helper

From a checkout, `sh tools/usb/write-usb.sh install /media/$USER/YOUR_USB` (or `write-usb.ps1` on
Windows) writes a bundle to a drive. Instead of overwriting old NEMESIS files, it moves them into a
backup folder.

## Build

```sh
./gradlew :app:testDebugUnitTest            # unit tests (tile order, tile support, grid math, presenter, search, backend)
sh tools/build_apk.sh                       # tests + lint + signed v1 APK → dist/Tilepad.apk
                                           # also builds/signs dist/Tweaks.apk
python3 tools/build_usb_bundles.py          # → dist/tilepad.zip + Tilepad-{install,update,uninstall}-usb.zip
sh tools/tweaks/test-host.sh                # host tests for the tweaks, no Gradle or device
```

`build_apk.sh` signs with `keystore/tilepad.jks`, whose password is in `keystore/password`. Both
are gitignored. **Back them up.** Future updates must be signed with the same key. If
`tools/signing-cert.sha256` exists, the build fails unless the APK's signing certificate has that
SHA-256 (`apksigner verify --print-certs dist/Tilepad.apk` prints it).

The debug build simulates the vehicle on an emulator (`ranchu`/`goldfish`). Launch it with
`--ez design_preview true` to get a fake Wi-Fi connection for screenshots.

## Releases

GitHub Actions builds, signs and publishes releases. Bump `versionName` and `versionCode` in
`app/build.gradle.kts` and `tweaks/build.gradle.kts`, and `package-version=` in
`nemesis-package/NEMESIS.data` (its `version=` is the descriptor format, not Tilepad's version).
Commit, then push a matching annotated tag. Its message is the whole release notes: a short
changelog (a subject line, then `- Added:`/`- Fixed:` bullets). The install steps live in the README.

```sh
git tag -a v1.0.1 -m "What changed"
git push origin main v1.0.1
```

The [release workflow](../.github/workflows/release.yml) checks that the versions match, runs
`build_apk.sh` (tests, lint, signing, certificate check) and `build_usb_bundles.py`, and publishes the
APKs, `tilepad.zip`, the three USB bundles and `SHA256SUMS`. The job holding the key can only read
the repo; a separate job publishes. Every push and pull request runs the same build with a
throwaway key.

The key comes from repository secrets:

```sh
base64 -i keystore/tilepad.jks | gh secret set TILEPAD_KEYSTORE_B64
gh secret set TILEPAD_STORE_PASSWORD < keystore/password
# only if the key's password differs from the store's: gh secret set TILEPAD_KEY_PASSWORD
```

## Adding firmware support

A submitted compatibility log is checked against a supported image with the scripts in
`tools/compat/` (triage, deodex, compare, transaction tables, patch check). Every log received is
kept outside the repo, and `tools/compat/corpus.py` deodexes each distinct firmware image once, so a
new feature's OEM calls can be checked against all of them at once. The steps are in the
`tilepad-compat` skill (`.claude/skills/tilepad-compat/SKILL.md`), and the evidence for each
supported image is recorded in [TWEAKS.md](TWEAKS.md#compatibility-and-ownership). None of this
ships in a release, and no firmware files are committed.

## Source map

| | |
| --- | --- |
| `MainActivity` | tiles, popups, preconditioning popup flow, Wi-Fi polling, TUNE knob navigation |
| `TileGrid`, `PagedGridMath` | square tiles on swipeable pages with dots, fixed core block on page one, long-press drag reorder across pages, edit mode |
| `TileView` / `StatusTile` / `WifiTile` / `PreconTile` | tile views |
| `AddShortcutPage`, `KeyPad` | screen picker and built-in keyboard |
| `Sheet`, `StatusCard` | popup layer |
| `TileOrder`, `TileSpec`, `TileSupport`, `GridMath`, `PreconPresenter`, `SearchFilter` | pure, unit-tested logic (`TileSupport` decides which core tiles the car can back) |
| `Preconditioning*`, `OemVehicleClient`, `VehiclePort` | Launchpad backend updates adapted to Tilepad's existing service and tile flow |
| `longstar/*` | the steering-wheel ★ hold service (evdev reader that never grabs or injects, hold timing, `StarHold`: the car's ★ action during a hold) |
| `RootShell` | `nemesis root -c` (what NEMESIS Manager uses) |
| `nemesis-package/bin/wifi-client.sh` | root side of *turn on Wi-Fi* (run through `RootShell`) |
| `tweaks/` | standalone Android settings APK, checkbox rows, help and option pages |
| `tweaks/…/addon/`, `AddonPage`, `AddonCtl`, `PackageFolder` | pages other packages describe in their `tilepad.properties`: the parser, the checked root commands, and the views (docs/TWEAKS.md) |
| `tweaks-core/` | shared in-process listeners, OEM adapters, config and reversible patch transactions |
| `nemesis-package/tweaks/patches/` | tiny, exact-file-gated recipes; no OEM APK/ODEX shipped |
| `tools/compat/` | compatibility-log checks (development only) |
