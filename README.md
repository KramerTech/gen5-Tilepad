# Tilepad

Tilepad is a launcher for the gen5W head unit, the Android 4.4 infotainment system in Hyundai and
Kia cars such as the IONIQ 5 and EV6. It gives you a grid of app shortcuts and status tiles. The
tiles control Wi-Fi and ADB, start and stop battery preconditioning by hand, and open a bundled set
of system tweaks. Tilepad is installed through [NEMESIS](https://github.com/xwtk/hmg_nemesis), and
its preconditioning is built on [Launchpad](https://github.com/paytondevs/gen5w-preconditioning)'s
backend. It is developed and tested on an IONIQ 5 (NEEV23 US).

![Tilepad on an IONIQ 5 head unit, showing the Wi-Fi, ADB, Tweaks and Precondition status tiles, the Nemesis Manager and Reboot Head Unit tiles, and shortcuts after them](docs/images/launcher.png)

To open Tilepad, hold the steering wheel's **★** (custom) button for about a second. Hold it again
to close Tilepad. A short press of ★ still does whatever Hyundai's Button settings assign to it.

## Features

- **Wi-Fi status**, on head units that have a Wi-Fi module. The tile shows the network, the IP
  address and the signal strength. Tap it to pick a network. If the Wi-Fi radio is still in its
  stock configuration, where it only runs as the hotspot for wireless CarPlay and Android Auto,
  the tile offers to turn on client mode first (see below).
- **Manual battery preconditioning**, on cars that support it. One tap starts it and one tap stops
  it.
- **Bundled Tweaks app**, opened from the permanent **Tweaks and Settings** tile beside
  **Precondition**. It has eleven tweaks, and they all start off:
  - **Automatic Daylight Saving Time** keeps the head unit's daylight saving switch in step with
    your region.
  - **Skip Startup Safety Brief** goes straight past the confirmation screen when the head unit
    starts.
  - **Automatic Heaters** turns on the heaters you choose when the head unit starts in the cold.
  - **Swap Steering-Wheel Track Buttons** makes the up button select the next track and the down
    button the previous one.
  - **Hide Right-Hand Nav Handle** hides the handle at the right edge of most screens, and also
    keeps Split Screen off.
  - **Disable Right-Hand Pop-Over** stops the brief media and information panels that pop up over
    the right side of the screen.
  - **Maintain Climate Sync** moves the passenger and driver temperatures together.
  - **Automatic Driver Only** selects Driver Only when the car starts, then Sync once the
    passenger buckles up.
  - **Auto-Open Phone Projection** opens Android Auto or CarPlay as soon as it is available.
  - **Charge-Away** switches Scheduled Charging off when you are away from Home and back on when
    you are at Home, and keeps your off-peak settings.
  - **Disable OTA Client** stops the head unit from checking for and downloading its own updates.
- **Package Manager**, in Tweaks and Settings. It updates Tilepad over Wi-Fi, and it installs and
  updates other NEMESIS packages straight from their GitHub releases.
- **Support**, in Tweaks and Settings. It saves a log to a USB drive so you can report a problem.
- **ADB toggle**. It turns ADB over Wi-Fi on and off. While ADB is on, any computer on the network
  can connect without approving a key. Turning ADB on also turns Wi-Fi on, which means wireless
  CarPlay and Android Auto are off.
- **Nemesis Manager** shortcut.
- **Reboot Head Unit**, which asks you to confirm first.
- **Shortcuts** to any screen, including Hyundai and system screens that have no icon.
- **Rearrange, rename and remove** tiles with a long press.

Some tiles only appear if the car has the hardware for them. On a gas car there is no Precondition
tile, and on a head unit without a Wi-Fi module there is no Wi-Fi tile. Preconditioning works on
the US and Korean IONIQ 5 and EV6 and on the US IONIQ 6, on the firmware versions it has been
checked against. On other firmware the tile won't send commands.

## Tiles

| Tile | Shows | Tap |
| --- | --- | --- |
| **Wi-Fi** *(only on units with a Wi-Fi module)* | Signal bars, the status (Connected, No network or Off), the network name, the IP address and the link speed. It reads *Hotspot only* while client mode is turned off. | Opens Android's Wi-Fi network picker, or offers to turn on client mode (see below). A long press shows the Wi-Fi MAC address, which you need to reserve a static IP on your router. |
| **Precondition** *(EVs only)* | The state (Off, Waiting, Active, Unavailable and so on), a spinner while waiting, and the elapsed time. A *Conditioning* pill (a small rounded label) appears while the car's own indicator is on. | See below. |
| **Tweaks and Settings** | An On/Off pill, which is on when any tweak is enabled, and a count of the tweaks that are on and off. | Opens the bundled `Tweaks.apk`. Each tweak has a checkbox and a detailed explanation, and Support and Package Manager come after the tweaks. |
| **ADB** | An On/Off pill, with the port number while ADB is on and *Port closed* while it is off. | Asks you to confirm, then turns ADB over Wi-Fi on or off (see below). |
| **Nemesis Manager** | | Opens NEMESIS Manager. The tile is dimmed if NEMESIS Manager isn't installed. |
| **Reboot Head Unit** | | Asks you to confirm, then restarts the head unit by running `nemesis root -c reboot`. |
| **Add shortcut** | | Opens a searchable list of every screen on the head unit, including Hyundai and system screens that have no icon. |

**Preconditioning with one button.** What a tap does depends on the tile's state:
- When it is Off, a tap sends Start and opens a progress popup. The popup shows *Waiting for BMS*,
  which usually takes 60 to 120 seconds, and changes to ✓ *Preconditioning active* once the car
  reports that preconditioning is on. If you closed the popup before then, it opens again on its
  own.
- When it is Waiting, a tap reopens the popup, which has a **Cancel request** button. A stray tap
  won't cancel the request.
- When it is Active, a tap turns preconditioning off. The popup shows *Turning off*, then *Off*.
- In any other state (Unavailable, Error, the car not reporting READY, and so on), a tap opens a
  popup with the car's reason.

The tile only shows Active when the car's own indicator says so, never just because enough time
has passed. The request keeps going after you close Tilepad.

**Hotspot mode and client mode.** The head unit has one Wi-Fi radio, and it runs in one of two
modes. In hotspot mode, your phone connects to the head unit for wireless CarPlay and Android
Auto. In client mode, the head unit connects to a Wi-Fi network, such as your home network or
your phone's hotspot. The radio can't be in both modes at once.

By default, stock US firmware has client mode turned off, so the radio only ever runs the hotspot.
The same is true of wireless CarPlay and Android Auto mods that were installed without their Wi-Fi
client option, such as HMG Leafia Plus without `LEAFIA-WIFI-CLIENT`. On those units the Wi-Fi tile
reads *Hotspot only*, and tapping it offers to turn client mode on. Once you agree, the popup
offers a reboot, and the tile reads *Reboot to finish* until the head unit has restarted. After
that, tapping the tile opens the network picker.

- While the head unit is connected to a Wi-Fi network, wireless CarPlay and Android Auto may not
  connect.
- This works alongside HMG Leafia Plus's own Wi-Fi client option, which makes the same change.
- If Tilepad turned client mode on, uninstalling Tilepad turns it back off after the next reboot.

**ADB over Wi-Fi.** The ADB tile turns ADB over Wi-Fi on and off. Tapping it while ADB is off asks
you to confirm, because turning ADB on has serious consequences:

> ⚠️ While ADB is on, **any computer that can reach the head unit can connect and get a
> root-capable shell**, with no key to approve. This head unit's adbd doesn't check keys over
> Wi-Fi. That includes computers reaching it through the telematics modem's cellular connection,
> not just your own Wi-Fi network. Only turn ADB on while you need it, and turn it off when you're
> done.

> ⚠️ **Wireless CarPlay and Android Auto don't work while Wi-Fi is on.** The head unit has one
> Wi-Fi radio. It either joins a network or runs the hotspot your phone uses for wireless CarPlay,
> never both. While ADB is on, Tilepad turns the hotspot off and Wi-Fi on, and it does so again at
> every boot.

- Turning ADB **on** makes the head unit listen for ADB on port 5555, now and after every reboot,
  until you turn it off. Connect from your computer with `adb connect <head-unit-ip>:5555`.
- ADB needs the head unit on the same network as your computer. So while ADB is on, Tilepad turns
  on Wi-Fi and joins your saved network, right away and again at every boot. If the Wi-Fi tile
  reads *Hotspot only*, turn on client mode from that tile first.
- Turning ADB **off** stops the head unit from listening. It leaves Wi-Fi as it is.
- The head unit serves **one computer at a time**. While one computer is connected, another
  computer's `adb connect` hangs and shows `offline` or `unauthorized` until the first one
  disconnects with `adb disconnect`.
- The ADB tile needs NEMESIS, which provides root. Without NEMESIS the tile says so and does
  nothing.
- An **update** keeps ADB in whatever state you left it. **Uninstalling** Tilepad turns ADB off if
  the tile turned it on.

**Arranging tiles.**
- Page one starts with a fixed block of six core tiles: Wi-Fi, ADB and Nemesis Manager on the top
  row, and Tweaks and Settings, Precondition and Reboot Head Unit below them. A thin vertical
  divider separates the block from your own tiles. A tile for a feature your car doesn't have is
  left out of the block.
- Your tiles fill the rest of page one, which has room for six of them. Any more continue on further
  pages, with dots at the bottom of the screen. Swipe left and right to move between pages. Later
  pages have no fixed block or divider.
- Long-press any tile, then drag it. Tiles wiggle while you are in edit mode.
- In edit mode, each shortcut gets a ✎ button on its top-left corner to rename it and a − button on
  its top-right corner to remove it. The six fixed tiles don't move. The Add shortcut tile can be
  moved, but it can't be removed or renamed. To carry a tile to the next page, drag it to the edge
  of the screen.
- To leave edit mode, tap **Done**, tap empty space, or press Back.
- New shortcuts are added just before the Add shortcut tile. If you rename a shortcut to an empty
  name, its original name comes back.

Search and rename use Tilepad's own built-in keyboard, so no Android keyboard app (IME) is needed.
Long-press ⌫ to clear what you typed. The TUNE knob also works: turn it to move the highlight and
press it to open the highlighted tile.

## Install (from USB, with no computer needed in the car)

1. **Uninstall Launchpad first** with its `Launchpad-uninstall-usb.zip`, then reboot. Tilepad
   replaces Launchpad, and it uses the same preconditioning backend and the same hold-to-open
   service. Tilepad refuses to install, whether from USB or from NEMESIS Manager, while any trace
   of Launchpad is left on the head unit, and its log says what it found. When it refuses, nothing
   on the head unit is changed.

   Tilepad also can't be installed alongside [HMG-API](https://github.com/VysseCodes/HMG-API),
   because both change the same system apps and settings. For example, HMG-API's ADB mod keeps ADB
   over Wi-Fi turned off. Uninstall HMG-API first. NEMESIS refuses to install either one while the
   other is installed.
2. Download **`Tilepad-install-usb.zip`** from the [latest release](https://github.com/KramerTech/gen5-Tilepad/releases/latest)
   and extract it so that its *contents* sit at the root of a FAT32 or exFAT drive. Nothing else
   from NEMESIS should be on the drive:
   ```text
   USB/
   ├── NEMESIS.data
   ├── packages/tilepad.zip      (don't unzip this)
   └── tilepad/{common,lib,install}.sh
   ```
3. In the car, with the car parked and the head unit fully booted, plug the drive into a USB data
   port and tap **Yes** on the NEMESIS prompt. Wait for the result dialog.
4. Reboot the head unit with the pinhole button and remove the drive. Then hold ★ on the steering
   wheel to open Tilepad.

The installer writes `tilepad-install.log` to the drive. If the install is refused or fails, that
log says why.

## Update

Updates keep your tiles, your tweak settings and the ADB state. There are three ways to update:

- **Package Manager, over Wi-Fi.** This needs Tilepad 1.2.0 or later. Older versions don't have
  Package Manager, so update them by USB or ADB instead. Connect the head unit to Wi-Fi (a phone
  hotspot works), open **Tweaks and Settings** → **Package Manager** and tap **Check for
  Updates**. If there is an update, tick it and tap **Install**. A full-screen window shows the
  download and the install, and the head unit restarts on its own when the installation is
  complete, so only do this while parked. Package Manager only uses Wi-Fi, never the car's
  built-in modem, and it only installs releases signed with Tilepad's key.
- **USB.** This works from any version. Use `Tilepad-update-usb.zip` exactly like the install
  above, then restart the head unit.
- **ADB.** Run `adb push tilepad.zip /data/local/tmp/`, then
  `adb shell /system/xbin/nemesis install --update /data/local/tmp/tilepad.zip`. Restart the head
  unit when the install has finished.

**Other NEMESIS packages.** Package Manager can also install and update other NEMESIS packages
straight from their developers' GitHub releases, including packages that aren't in the NEMESIS
Package Store. Under **Configure Other Software**, link a package to the GitHub repository it
comes from. If the package is already on the car, it is only linked. If it isn't, it gets
installed. After that, **Check for Updates** covers it along with Tilepad. Package Manager only
manages Tilepad and the packages you've linked, so a package you installed from NEMESIS Manager and
didn't link keeps updating through NEMESIS Manager. The bin icon beside a linked package asks
whether to uninstall it or only unlink it. Only link repositories you trust, because their releases
install with root access.

## Uninstall

Use `Tilepad-uninstall-usb.zip` the same way as the install, then restart the head unit. The
uninstaller first undoes every tweak, putting each changed system file back exactly as it was.
Then it removes Tilepad and its saved settings, including the Charge-Away Home location. It also
turns ADB and Wi-Fi client mode back off if Tilepad turned them on. If a tweak can't be restored,
the uninstaller stops before removing Tilepad's apps and backups, so the car is never left
half-patched, and `tilepad-uninstall.log` on the drive says why.

## Release files

| Release file | Use |
| --- | --- |
| `Tilepad-install-usb.zip` | A first install |
| `Tilepad-update-usb.zip` | Updating an existing install, keeping your tiles |
| `Tilepad-uninstall-usb.zip` | Removing Tilepad |
| `tilepad.zip` | The NEMESIS package itself, for `nemesis install` over ADB. Don't extract it onto a drive |
| `tilepad.zip.sig` | The package's signature, which Check for Updates verifies before installing |
| `Tilepad.apk`, `Tweaks.apk` | The signed apps, for reference |
| `SHA256SUMS` | Checksums of the files above |

## Supported cars

Tilepad can work on any gen5W head unit. If something shows an error on your car, or a tweak is
unavailable, submit a compatibility report (see below) so that support can be added.

The tweaks check the car's exact firmware files, so they work on any car that runs a supported
firmware image. The supported images are USA Hyundai `V012.010.240822`, `V015.012.260507` and
`V016.010.260903`, and USA Kia `V015.012.260507`, which cover the IONIQ 5 and EV6, for example.
Heaters that a trim doesn't have are skipped. How each tweak works, and what it changes, is
explained in [docs/TWEAKS.md](docs/TWEAKS.md).

**Submitting a compatibility report.** Open **Tweaks and Settings**, select an unavailable tweak,
plug in a USB drive and tap **Capture Compatibility Log to USB**. Then attach the
`tilepad-compat-….zip` from the drive to a
[compatibility issue](https://github.com/KramerTech/gen5-Tilepad/issues/new?template=compatibility.md).
The log holds the head unit's software version, file hashes, the firmware code the tweaks rely on,
and numeric vehicle-equipment readings. It doesn't hold your location, your Charge-Away Home or
any personal names.

**Reporting a problem.** If a tweak doesn't take effect, or something happens that shouldn't, save
a log while the problem is still showing, or as soon as you notice it and before you turn the car
off. Plug in a USB drive, open **Tweaks and Settings** → **Support** and tap **Save Log to USB**.
Attach the `tilepad-log-….zip` from the drive to a
[problem report](https://github.com/KramerTech/gen5-Tilepad/issues/new?template=problem.md) and
say what you expected and what you saw instead. Besides everything the compatibility report holds,
this log covers the last few minutes: what was on screen, which apps opened, the messages from the
right-hand panel and the startup screen, Tilepad's service log, and whether each patched file is
still as Tilepad set it. Media, call, contact, radio and driver-profile messages are left out.

## Troubleshooting

- **Holding ★ does nothing.** Open NEMESIS Manager → Services and check that `tilepad/longstar`
  is running.
- **A tweak you changed hasn't taken effect.** The tweaks that change system files take effect at
  the next head-unit restart. The line under the tweak's checkbox says when a change is still
  waiting for a restart.
- **A tweak is unavailable.** Either your firmware isn't supported yet, or another mod has already
  changed the same file. Select the tweak to see which it is. For unsupported firmware, submit a
  compatibility report as described above. For another mod, turn off that mod's matching patch
  first.
- **Check for Updates fails.** It needs Wi-Fi with internet access, and the head unit's clock has
  to be set before GitHub's certificate can be checked. The message in Package Manager says which
  one is the problem.
- **The install window says “Restart Needed”.** The updates were installed, but the head unit
  didn't restart on its own. Tap **Close**, then turn the car off and on again.
- **The install window says “Nothing Was Installed”.** Tap **Close**, then open Package Manager
  to see what went wrong with each package.
- **A USB install, update or uninstall fails.** Read the `tilepad-install.log`,
  `tilepad-update.log` or `tilepad-uninstall.log` that it wrote to the drive. NEMESIS's own log is
  under NEMESIS Manager → Logs → Copy to USB.
- **Anything else.** Save a support log by opening **Tweaks and Settings** → **Support** and
  tapping **Save Log to USB**, then open a
  [problem report](https://github.com/KramerTech/gen5-Tilepad/issues/new?template=problem.md), as
  described above.

## More

[docs/TECHNICAL.md](docs/TECHNICAL.md) explains how Tilepad works and how to build and release it.

Credits: [NEMESIS](https://github.com/xwtk/hmg_nemesis), Launchpad by paytondev,
[Ioniq5_CAN](https://github.com/tylerharvey/Ioniq5_CAN),
[gen5w-utils](https://github.com/Helloyunho/gen5w-utils).

## Contributing

Pull requests are welcome. Each one should be a single bug fix or feature that has been tested on
a car. Please attach a support log, include screenshots of anything that appears on screen, and
check that uninstalling Tilepad still puts the car back the way it was. The details, and the rules
the code follows, are in [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Copyright © 2026 Sean Kramer. Tilepad is free software under the
[GNU General Public License v3.0](LICENSE). You can use it, change it, share it and sell it, as
long as anything you distribute that is built from it is also under GPLv3 and comes with its
source.

These parts aren't Tilepad's to license, and the license doesn't cover them:

- The preconditioning backend comes from [Launchpad](https://github.com/paytondevs/gen5w-preconditioning)
  by paytondev. Launchpad has no license, so paytondev keeps all rights to it. That covers
  `OemVehicleClient`, `PreconditioningController`, `PreconditioningProfile`,
  `PreconditioningService`, `PreconditioningSnapshot` and `VehiclePort` in
  `app/src/main/java/com/tilepad/`, and `PreconditioningControllerTest` and
  `PreconditioningProfileTest` in `app/src/test/java/com/tilepad/`.
- The patch recipes in `nemesis-package/tweaks/patches/` contain hashes, offsets and short runs of
  bytes from Hyundai's and Kia's own software, which belong to them.
- Code based on [Cabinwright tweaks](https://github.com/cabinwright/tweaks) keeps its MIT license,
  which is in `nemesis-package/notices/Cabinwright-MIT.txt`.
