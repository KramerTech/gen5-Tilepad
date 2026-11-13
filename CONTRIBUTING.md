# Contributing to Tilepad

Pull requests are welcome, and you don't need to open an issue first. Tilepad changes system files
on people's cars, so every change has to be tested on a real car and has to come back out cleanly.
This page covers what a pull request needs and the rules the codebase follows.

If you want Tilepad to support your car's firmware, open a
[compatibility issue](https://github.com/KramerTech/gen5-Tilepad/issues/new?template=compatibility.md)
with the log instead of a pull request. Adding a firmware image means comparing it against every
image seen so far, and the archive of firmware it's compared against can't be shared.

## What every pull request needs

- **One bug fix or one feature.** Keep unrelated fixes, refactors and formatting out of it, even
  small ones, and send them as their own pull requests. That way each change can be reviewed,
  tested and reverted on its own.
- **Testing on a car.** Install your build on a gen5W head unit and use the change the way a driver
  would. The emulator and the unit tests are a good start, but they don't replace the car. In the
  pull request, say which car you tested on (make, model, model year and region), the head unit
  software version, and what you did.
- **A support log.** After testing, while the change is still in effect, open **Tweaks and
  Settings** → **Support** and tap **Save Log to USB**. Attach the `tilepad-log-….zip` to the pull
  request. It shows the firmware, what was on screen, Tilepad's service log and the state of every
  patched file, and it leaves out your location and personal names.
- **Screenshots for anything visible.** If the change affects what's on screen, attach before and
  after screenshots from the car (see [Screenshots](#screenshots)). Blur or crop out your VIN,
  license plate and location.
- **A clean uninstall.** Uninstall Tilepad with `Tilepad-uninstall-usb.zip` built from your branch,
  restart the head unit, and check that everything your change touched is back the way it was
  before Tilepad was installed (see [Leave nothing behind](#leave-nothing-behind)).
- **Passing checks.** `sh tools/build_apk.sh` runs the unit tests, lint and host tests, and GitHub
  runs the same build on every pull request. Add tests for new logic where the existing code has
  them.
- **Updated docs.** If the change alters what Tilepad does, update the README, the tweak's help
  text, and [docs/TWEAKS.md](docs/TWEAKS.md) or [docs/TECHNICAL.md](docs/TECHNICAL.md) to match.

Leave version numbers and release notes alone; they change only when a release is made.

## Building and installing your build

You need JDK 21 and the Android SDK with build-tools 36.0.0. Release builds are signed with a key
that isn't in the repo, so sign yours with your own key, the same way the pull request check does:

```sh
keytool -genkeypair -keystore ~/tilepad-dev.jks -alias tilepad -storepass devpass \
    -keypass devpass -dname CN=tilepad-dev -keyalg RSA -keysize 2048 -validity 3650
export TILEPAD_KEYSTORE=~/tilepad-dev.jks TILEPAD_STORE_PASSWORD=devpass TILEPAD_CERT_PIN_FILE=/nonexistent
sh tools/build_apk.sh
python3 tools/build_usb_bundles.py
```

That builds `dist/tilepad.zip` and the three USB bundles. Install with
`Tilepad-update-usb.zip` (or `Tilepad-install-usb.zip` on a car without Tilepad), or over ADB:

```sh
adb push dist/tilepad.zip /data/local/tmp/
adb shell /system/xbin/nemesis install --update /data/local/tmp/tilepad.zip
```

Restart the head unit once the install has finished. To go back to an official release afterwards,
install that release's update bundle the same way.

## Screenshots

Turn on ADB with Tilepad's ADB tile, then from a computer on the same network:

```sh
adb connect <head unit IP>:5555
adb shell screencap -p /data/local/tmp/shot.png
adb pull /data/local/tmp/shot.png
```

A photo of the screen is fine too, if ADB isn't an option.

## How Tilepad is built

These are the rules the existing code follows. A change that breaks one won't be merged, however
useful it is.

### Leave nothing behind

Uninstalling Tilepad has to put the car back exactly the way it was. `tune-service/scripts/remove.sh`
is the whole uninstall, and anything your change creates or changes has to be undone there:

- Before changing a system file, keep a verified backup of the stock file, and restore from that
  backup, not from a recipe, so a restore still works after a recipe changes or disappears.
- Anything that must survive an update has to live outside the apps' data, because every NEMESIS
  update uninstalls and reinstalls the APKs and replaces `/data/nemesis/apps/tilepad/`. Put it under
  `/data/nemesis/backup/tilepad/` or `/data/nemesis/backup/tilepad-tweaks/`, with the existing
  settings and records, and make sure uninstall removes it.
- Settings that Tilepad turns off on the car (the OTA client, for example) are turned back on by
  turning the tweak off and by uninstalling.
- Undo only what Tilepad itself did, and only while it's still what Tilepad left. Uninstall turns
  ADB off only if the ADB tile turned it on, and removes the Wi-Fi client file only if it's
  unchanged since Tilepad added it.
- Turning a tweak off restores exactly what turning it on changed, and leaves every other tweak as
  it was.
- An interrupted install, update or write must not leave the car half-patched. Follow the existing
  journal and rollback code rather than writing files directly.

### Never touch what isn't Tilepad's

- Patch only files whose exact hash is known. Never loosen a hash check to make another car or
  firmware pass; support for a new firmware is added as its own entry in `Firmware.java`.
- If another mod has already changed a file, show the tweak as unavailable. Don't adopt or
  overwrite someone else's patch.
- With a tweak off, the car behaves exactly as stock. The ★ reader only listens: it never grabs
  the key device or injects key presses.
- Don't commit firmware, ODEX or APK files from the car, disassembly, or raw vehicle logs. Patch
  recipes hold only hashes, offsets and the few bytes they replace.

### Safety on the road

- Nothing Tilepad draws may cover the reverse camera. Full-screen windows use
  `TYPE_SYSTEM_ALERT`, which sits below it.
- Never open the rear camera from Tilepad. A stuck client blocks the stock reverse view until the
  head unit restarts.
- Don't make the head unit restart, or block the screen, while the car could be moving.

### Code

- The head unit runs Android 4.4 (API 19). Code is Java 8, but it can only use APIs that exist on
  API 19 (no `java.time` or streams), and Tilepad has no libraries besides JUnit for tests. Don't
  add one.
- Match the code around your change: its naming, comment density and structure.
- Every call into Hyundai's or Kia's code (Binder transaction codes, service methods, patched
  methods) is checked against each supported firmware image before it ships. Say in the pull
  request which calls you added and which firmware you tested them on; they'll be checked against
  the other images during review.
- The preconditioning backend tracks [Launchpad](https://github.com/paytondevs/gen5w-preconditioning)
  (see [docs/PRECONDITIONING-UPSTREAM.md](docs/PRECONDITIONING-UPSTREAM.md)). Send fixes to it
  upstream first; they're ported into Tilepad from there.

### On-screen text

Drivers read Tilepad's text at a glance, so write it the way you'd say it out loud.

- Use full, natural sentences, not clipped phrases chained with colons.
- Name places concretely ("on the car", "in Package Manager"), never "here".
- Write the car's own setting and button names in bold: `**Scheduled Charging**`. Put Tilepad's
  own buttons in curly quotes when telling someone to press one: Tap “Check for Updates”.
- Show a result in a window, not as a line of text under the buttons.

## License

By opening a pull request, you agree that your contribution is licensed under the
[GNU General Public License v3.0](LICENSE), like the rest of Tilepad.
