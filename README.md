# Tilepad

A minimal grid launcher for Hyundai's gen5W head unit (Android 4.4), installed through
[NEMESIS](https://github.com/xwtk/hmg_nemesis). Its battery preconditioning is based on
[Launchpad](https://github.com/paytondevs/gen5w-preconditioning)'s backend.
It's developed and tested on an IONIQ 5 (NEEV23 US).

![Tilepad on an IONIQ 5 head unit: Wi-Fi, ADB, Tweaks and Precondition status tiles, Nemesis Manager and Reboot Head Unit, then shortcuts](docs/images/launcher.png)

Hold the steering wheel's **★** (custom) button for about a second to open it. Hold again to close it.
A short ★ press still runs whatever Hyundai's Button settings assign to it.

## Features

- **Wi-Fi status** (if the head unit has a Wi-Fi module): network, IP, signal. Tap to pick a network.
  On a unit whose Wi-Fi only runs the CarPlay/Android Auto hotspot, tap to turn on the Wi-Fi client.
- **Manual battery preconditioning** (if available on the vehicle): one-tap start and stop.
- **Bundled Tweaks app**: automatic DST, startup-brief bypass, configurable cold-boot
  heaters, track-button swap, independent right-handle/pop-over suppression, and
  climate tweaks: Maintain Climate Sync (passenger and driver temperatures move together) and
  Automatic Driver Only (Driver Only at start, Sync once the passenger buckles up).
  Auto-Open Phone Projection opens AA/CarPlay when available; Charge-Away switches
  Scheduled Charging off away from Home and back on at Home, preserving off-peak settings.
  A permanent Tweaks tile sits beside Precondition. All ten tweaks start off.
- **ADB toggle**: turn ADB over Wi-Fi on and off. While it's on, any computer on the network can connect without approving a key. Turning it on also turns on Wi-Fi (so wireless CarPlay/Android Auto are off).
- **Nemesis Manager** shortcut.
- **Reboot Head Unit**, with confirmation.
- **Shortcuts** to any screen, including Hyundai and system screens that have no icon.
- **Rearrange, rename and remove** tiles with a long press.

"If available" comes from the car itself. Hyundai's firmware declares each car's options as
system features. The Wi-Fi tile needs `com.hkmc.software.wifi.module`, and the preconditioning
tile needs `com.hkmc.hardware.ev`. On a gas car or a unit without Wi-Fi, those tiles just don't
appear, and Tilepad never contacts the vehicle service. Preconditioning supports ten exact
upstream firmware profiles across US/Korean IONIQ 5 and EV6 units and the US IONIQ 6.
Other firmware cannot start preconditioning or send commands: Android version, device, software
identity and both OEM framework hashes must match a supported profile.

## Tiles

| Tile | Shows | Tap |
| --- | --- | --- |
| **Wi-Fi** *(Wi-Fi module only)* | signal bars, Connected / No network / Off, network name, IP, link speed. *Hotspot only* when the client is off | Android's Wi-Fi network picker, or *turn on Wi-Fi* (see below). Long-press: the Wi-Fi MAC address, for a static IP reservation |
| **Precondition** *(EVs only)* | Off / Waiting / Active / Unavailable…, spinner while waiting, elapsed time, a *Conditioning* pill while the car's own indicator is on | see below |
| **Tweaks** | On/Off pill (on when any tweak is enabled); how many are enabled and how many are off | opens the bundled `Tweaks.apk`; each feature has a checkbox and detailed explanation |
| **ADB** | On/Off pill; the port while on, *Port closed* while off | asks to confirm, then turns ADB over Wi-Fi on or off (see below) |
| **Nemesis Manager** | | NEMESIS Manager (dimmed if it's not installed) |
| **Reboot Head Unit** | | asks to confirm, then hard-reboots the head unit (`nemesis root -c reboot`) |
| **Add shortcut** | | searchable list of every screen (apps, plus Hyundai/system screens with no icon) |

**Preconditioning, one button:**
- Off → tap: sends Start and pops up a progress popup. It shows *Waiting for BMS* (usually 60–120 s) and turns into a ✓ *Preconditioning active* when the car reports it. If the popup was closed by then, it pops up on its own.
- Waiting → tap: reopens the popup, which has **Cancel request**. A stray tap won't cancel.
- Active → tap: turns it off. The popup shows *Turning off*, then *Off*.
- Anything else (Unavailable, Error, not READY…) → tap: a popup with the car's reason.

"Active" only ever comes from the car's own indicator, never from a timer. The backend is
Launchpad's, unchanged: firmware-hash gate, ownership journal, cancel-on-recovery, and a
foreground service that keeps the request alive after the app closes.

**Turning on the Wi-Fi client.** Stock US firmware, and wireless CarPlay/Android Auto mods
installed without their Wi-Fi client option (HMG Leafia Plus's `LEAFIA-WIFI-CLIENT`), use the
radio only for the projection hotspot. The tile then reads *Hotspot only*, and tapping it offers
to turn the client on. That does what Leafia's option does: it adds
`/system/etc/permissions/android.hardware.wifi.xml` (Hyundai's Wi-Fi service and settings app
read it as "client supported") and sets `persist.wifi.state=3` (client + wireless). It doesn't
write `build.prop`; the persistent property survives reboots on its own. The popup then offers a
reboot, since Android reads features at boot. Until then, the tile reads *Reboot to finish*.

- It's idempotent and never touches a file that's already there, so it can't fight Leafia. If
  Leafia is installed later with its client option, it writes the same file. If Leafia was
  installed first without it, Leafia's uninstall restores its snapshot and removes the client
  again, and the tile offers it again.
- There's one Wi-Fi radio. While it's on a network, wireless CarPlay/Android Auto may not connect.
- Uninstalling Tilepad turns the client back off (after the reboot) if Tilepad turned it on:
  it removes the file, if it's still exactly what Tilepad wrote, and puts `persist.wifi.state`
  back. It leaves the client alone while HMG Leafia Plus is installed, since Leafia's option
  uses the same file.

**ADB over Wi-Fi.** The ADB tile turns ADB over Wi-Fi on and off. Tapping it when it's off asks
to confirm, because of what "on" means:

> ⚠️ While ADB is on, **any computer that can reach the head unit can connect and get a
> root-capable shell**, with no key to approve: this head unit's adbd doesn't check keys over
> Wi-Fi. That includes the telematics modem's cellular connection, not just your Wi-Fi. Only turn
> it on while you need it, and turn it off when you're done.

> ⚠️ **Wireless CarPlay and Android Auto don't work while Wi-Fi is on.** The head unit has one
> Wi-Fi radio: it either joins a network or runs the hotspot your phone uses for wireless
> CarPlay, not both. While ADB is on, Tilepad turns the hotspot off and Wi-Fi on, at every boot
> too.

- **On** sets the firmware's own switch for ADB over TCP (`persist.service.adb.tcp.enable`), so
  adbd listens on port 5555, now and after every reboot, until you turn it off. Connect with
  `adb connect <head-unit-ip>:5555`.
- **Wi-Fi.** ADB needs the head unit on the same network as your computer, so while ADB is on,
  Tilepad turns on Wi-Fi (joining your saved network), right away and again at every boot. On a
  unit whose Wi-Fi only runs the CarPlay hotspot, turn on Wi-Fi from the Wi-Fi tile first.
- **Off** clears that switch and stops adbd, so nothing listens for ADB over TCP. It leaves Wi-Fi
  as it is.
- The head unit serves **one computer at a time**: while one is connected, another's
  `adb connect` hangs and shows `offline` or `unauthorized` until the first disconnects
  (`adb disconnect`).
- It needs NEMESIS (for root); without it the tile says so and does nothing.
- An **update** keeps ADB in whatever state you left it. **Uninstalling** Tilepad turns it off if
  the tile turned it on.

**Arranging:**
- Page one starts with a fixed block of six core tiles: Wi-Fi, ADB and Nemesis Manager on top, Tweaks, Precondition and Reboot Head Unit below. A thin vertical divider separates it from your tiles; unsupported vehicle features are still omitted.
- Your tiles fill the rest of page one (six on the car). More continue on further pages, with dots at the bottom: swipe left and right. Later pages have no fixed block or divider.
- Long-press any tile, then drag it. Tiles wiggle while you're in edit mode.
- In edit mode, shortcuts get ✎ (rename) on the top-left and − (remove) on the top-right. The six fixed tiles don't move; Add shortcut can be moved but not removed or renamed. Drag a tile to a screen edge to carry it to the next page.
- Leave edit mode with **Done**, by tapping empty space, or with Back.
- New shortcuts are added just before the Add tile. Renaming to an empty name restores the original name.

Search and rename use a built-in keyboard, so no Android IME is needed. Long-press ⌫ to clear.
The TUNE knob also works: turn it to move the highlight and press it to open the tile.

## Install (USB, no computer needed in the car)

1. **Uninstall Launchpad first** with its `Launchpad-uninstall-usb.zip`, then reboot. Tilepad
   replaces it (same preconditioning backend and hold-to-open service). Tilepad refuses to
   install (from USB or NEMESIS Manager) while any trace of Launchpad is left, and its log
   says what it found. Nothing is changed when it refuses.
2. Download **`Tilepad-install-usb.zip`** from the [latest release](https://github.com/KramerTech/gen5-Tilepad/releases/latest)
   and extract it so its *contents* sit at the root of a FAT32/exFAT drive, with nothing else
   from NEMESIS on it:
   ```text
   USB/
   ├── NEMESIS.data
   ├── packages/tilepad.zip      (don't unzip this)
   └── tilepad/{common,lib,install}.sh
   ```
   Or use `sh tools/usb/write-usb.sh install /media/$USER/YOUR_USB` (it moves old NEMESIS files
   into a backup folder instead of overwriting them).
3. In the car (parked, head unit fully booted), plug the drive into a USB data port and tap
   **Yes** on the NEMESIS prompt. Wait for the result dialog.
4. Reboot the head unit (pinhole button), remove the drive, and hold ★ on the steering wheel.

The installer writes `tilepad-install.log` to the drive. **Update** works the same way with
`Tilepad-update-usb.zip` and keeps your tiles. **Uninstall** uses `Tilepad-uninstall-usb.zip`.

The package lives in `/data/nemesis/apps/tilepad/`. The installer places
`/system/app/Tilepad.apk` and `/system/app/TilepadTweaks.apk`; NEMESIS still runs just
`tilepad/longstar`. Enabled tweak listeners share that process. Explicitly enabled static
tweaks change exact supported system files with verified stock backups; disabling restores
them, and uninstall restores owned tweaks before removing the APKs, then deletes Tilepad's
saved tweak settings (including the Charge-Away Home location). Turning on the Wi-Fi
client remains a separate confirmed action (see above).

The first Tweaks draft targets the captured IONIQ 5 firmware. Feature behavior,
compatibility, overhead, ownership and recovery are in [docs/TWEAKS.md](docs/TWEAKS.md).
The [mission/checkpoint](docs/TWEAKS-MISSION.md) records completed host validation and
remaining limits; [the consolidated checklist](docs/TWEAKS-DEVICE-CHECKLIST.md) covers
the emulator and car checks still required. In particular, the new pop-over patch's
runtime behavior is not yet verified on a head unit.

## Car test checklist

Things that can't be verified on an emulator, in rough priority order:

- [ ] Holding ★ on the steering wheel opens Tilepad, and holding it again closes it; a short ★ press still runs its stock action. If not, check NEMESIS Manager → Services → `tilepad/longstar`.
- [ ] Grid fit: expect 6 columns × 2 rows of ~185dp squares, clear of the split-screen handle on the right.
- [ ] Wi-Fi tile: name, IP and signal look right. Tapping opens the network picker.
- [ ] On a hotspot-only unit: the Wi-Fi tile reads *Hotspot only*. *Turn on* → *Reboot* → the tile shows the network state and opens the picker.
- [ ] Precondition: shows *Off · Tap to start* when READY with conditioning enabled in Hyundai's settings. Start → Waiting → Active (60–120 s), then tap → Off.
- [ ] Nemesis Manager tile opens Manager.
- [ ] Add shortcut: the list loads, the keyboard filters it, and an added tile launches its screen.
- [ ] Reboot Head Unit: confirm → the head unit restarts. If NEMESIS's root channel refuses, the popup shows its output.

If something fails, `adb logcat | grep -i tilepad` (with ADB enabled) or NEMESIS Manager →
Logs → Copy to USB helps.

## Build

```sh
./gradlew :app:testDebugUnitTest            # unit tests (tile order, tile support, grid math, presenter, search, backend)
sh tools/build_apk.sh                       # tests + lint + signed v1 APK → dist/Tilepad.apk
                                           # also builds/signs dist/Tweaks.apk
python3 tools/build_usb_bundles.py          # → dist/tilepad.zip + Tilepad-{install,update,uninstall}-usb.zip
```

`build_apk.sh` signs with `keystore/tilepad.jks`, whose password is in `keystore/password`. Both
are gitignored. **Back them up.** Future updates should be signed with the same key. If
`tools/signing-cert.sha256` exists, the build fails unless the APK's signing certificate has that
SHA-256 (`apksigner verify --print-certs dist/Tilepad.apk` prints it).

### Releases

GitHub Actions builds, signs and publishes releases. Bump `versionName` (and `versionCode`) in
`app/build.gradle.kts` and `package-version=` in `tune-service/NEMESIS.data` (its `version=` is
the descriptor format, not Tilepad's version), commit, then push a matching tag:

```sh
git tag -a v1.0.1 -m "What changed"   # the message becomes the release notes
git push origin v1.0.1
```

The [release workflow](.github/workflows/release.yml) checks that the three versions match, runs
`build_apk.sh` (tests, lint, signing, certificate check) and `build_usb_bundles.py`, and publishes the
APK, `tilepad.zip`, the three USB bundles and `SHA256SUMS`. The job holding the key can only read the
repo; a separate job publishes. Every push and pull request runs the same build with a throwaway key.

The key comes from repository secrets:

```sh
base64 -i keystore/tilepad.jks | gh secret set TILEPAD_KEYSTORE_B64
gh secret set TILEPAD_STORE_PASSWORD < keystore/password
# only if the key's password differs from the store's: gh secret set TILEPAD_KEY_PASSWORD
```

The debug build simulates the vehicle on an emulator (`ranchu`/`goldfish`). Launch it with
`--ez design_preview true` to get a fake Wi-Fi connection for screenshots.

### Source map

| | |
| --- | --- |
| `MainActivity` | tiles, popups, preconditioning popup flow, Wi-Fi polling, TUNE knob navigation |
| `TileGrid`, `PagedGridMath` | square tiles on swipeable pages with dots, fixed core block on page one, long-press drag reorder across pages, edit mode |
| `TileView` / `StatusTile` / `WifiTile` / `PreconTile` | tile views |
| `AddShortcutPage`, `KeyPad` | screen picker and built-in keyboard |
| `Sheet`, `StatusCard` | popup layer |
| `TileOrder`, `TileSpec`, `TileSupport`, `GridMath`, `PreconPresenter`, `SearchFilter` | pure, unit-tested logic (`TileSupport` decides which core tiles the car can back) |
| `Preconditioning*`, `OemVehicleClient`, `VehiclePort` | Launchpad backend updates adapted to Tilepad's existing service and tile flow |
| `longstar/*` | the steering-wheel ★ hold service (passive evdev reader, hold timing) |
| `RootShell` | `nemesis root -c` (what NEMESIS Manager uses) |
| `tune-service/bin/wifi-client.sh` | root side of *turn on Wi-Fi* (run through `RootShell`) |
| `tweaks/` | standalone Android settings APK, checkbox rows, help and option pages |
| `tweaks-core/` | shared in-process listeners, OEM adapters, config and reversible patch transactions |
| `tune-service/tweaks/patches/` | tiny, exact-file-gated recipes; no OEM APK/ODEX shipped |

Preconditioning compatibility and safety notes are in Launchpad's
[COMPATIBILITY.md](https://github.com/paytondevs/gen5w-preconditioning/blob/main/COMPATIBILITY.md).
The pinned profiles, imported fixes and validation are recorded in
[the upstream integration notes](docs/PRECONDITIONING-UPSTREAM.md).

Credits: [NEMESIS](https://github.com/xwtk/hmg_nemesis), Launchpad by paytondev,
[Ioniq5_CAN](https://github.com/tylerharvey/Ioniq5_CAN),
[gen5w-utils](https://github.com/Helloyunho/gen5w-utils).
