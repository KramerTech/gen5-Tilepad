# Bundled Tweaks

`Tweaks.apk` (`com.tilepad.tweaks`) is a separate settings app installed with
Tilepad. The permanent **Tweaks** tile occupies the lower-left corner of the fixed
core block on page one, with Precondition and Reboot Head Unit to its right; Wi-Fi,
ADB and Nemesis Manager occupy the upper row. A thin vertical divider separates the
block from the user's tiles, which continue on further swipeable pages when they
don't fit. Existing user-tile order and shortcuts are retained.

The Tweaks tile uses the same status-card format as the other three: glyph,
status pill, caption, headline, detail and hint. Any enabled tweak gives an **On**
status; none gives **Off**. The detail shows the enabled count out of the total.
The count is read when the launcher resumes through the lightweight root
`summary` command; it does not hash OEM APKs, access vehicle services, poll while
the launcher is closed, or claim that unknown settings are off. Counts refer to
enabled selections, including any file changes waiting for a restart.

All ten tweaks start **off**. Under each checkbox a sentence or two describes the current state;
file tweaks say when a change still waits for a head-unit restart (the runtime records
which file tweaks were configured when the boot began, `applied.<id>`, and `settings`
reports `pending.<id>`). The app follows the stock EV Settings layout: a
centered title without search, a scrolling list of tweak names on the left, the
selected tweak's checkbox and options in the middle, and its full explanation
on the right. Each pane scrolls independently. DST and heater options appear
when enabled and save immediately. The installed EV Settings row and checkbox
drawables are loaded at runtime when available; no OEM assets are bundled.
The fallback follows the same dimensions, colors and typography. Selection is
remembered, and night mode follows the system configuration. All DST schedules
fit in the middle pane without scrolling at the head unit's normal resolution.

Opening the app and reading back a save use the lightweight root `settings`
command: configuration plus small listener/restart status files. It does not hash
system APKs or probe the vehicle interface. Full compatibility verification still
runs before a system-file change and remains available through the diagnostic `status`
command. Once settings have loaded, the app runs `status` once in the background so an
unsupported tweak shows why before its checkbox is tried. Unsupported or conflicting files
are refused when a change is requested. An interrupted transaction is recovered before
settings become editable.

All controls share one optimistic save queue. Values update immediately; edits
save together after a 300 ms pause. While a command runs, newer edits replace
pending values by key rather than building a backlog. Controls and navigation
stay usable. Replies merge beneath newer edits, leaving the app flushes queued
changes, and failures reconcile against persisted settings.

The root `update KEY VALUE …` command validates the complete batch, commits it,
and returns settings in the same invocation. Unchanged settings do nothing.
Heater options, heater enablement, and DST schedule changes use an atomic config
write without patch audits. Vehicle validation runs only when enabling a vehicle
feature or changing an enabled DST schedule. DST disable retains its restoration
transaction. File switches verify only their affected recipes; the shared right
panel recipe still composes both panel switches. Recovery persists this scope,
while legacy pending transactions and explicit Restore retain full verification.
The diagnostic `status` command still audits compatibility. The listener ignores
config edits that do not change DST, avoiding unnecessary OEM calls and state-lock
contention. No long-lived root server or additional background service was added.
The older heater-only `options` command remains supported for compatibility.

| Switch | Behavior | Extra settings | Restart |
| --- | --- | --- | --- |
| Automatic Daylight Saving Time | Synchronizes the OEM DST flag and Date/Time checkbox | US/Canada, Europe, or none | No |
| Skip Startup Safety Brief | Uses the OEM agreement path before consent-screen construction, at boot and subsequent construction requests | None | Yes |
| Automatic Heaters | One cold-boot request to selected heaters; never continuously overrides manual settings | Threshold, °F/°C, driver/wheel/passenger checkboxes, independent seat levels 1–3 | Next head-unit boot |
| Swap Steering-Wheel Track Buttons | Reverses keys 34/35; up advances, down goes back | None | Yes |
| Hide Right-Hand Nav Handle | Forces the stock handle controller's hidden path; shown under the pop-over entry and always on while the pop-over is disabled | None | Yes |
| Disable Right-Hand Pop-Over | Blocks the shared right-side OSD request path and renderer | None | Yes |
| Maintain Climate Sync | A passenger temperature change moves the driver temperature to match, then turns Sync back on | None | No |
| Automatic Driver Only | Driver Only every time the car is turned on; Sync once the passenger belt is buckled (one way, until the next car start) | None | No |
| Auto-Open Phone Projection | Opens AA/CarPlay once when a session becomes available, independently of the right pop-over | None | No |
| Charge-Away | Scheduled Charging off beyond 1.5 miles of Home, on within 1 mile; retains state between boundaries and preserves the off-peak-only choice | Set Home from the current GPS location, update it, or clear it; coordinates shown | No |

The broad pop-over switch hides other notices using that same OSD panel as well.
The stock **Display → Media change notifications** preference is insufficient for
complete suppression: the captured firmware's own help says physical-button track
changes still display media information. This implementation leaves that factory
preference alone and patches the shared display path, including forced requests.
It does not disable the entire RightScreenHome package.

## Runtime and overhead

The only permanent NEMESIS service remains `tilepad/longstar`. `LongStarDaemon` starts
`TweaksRuntime` in a thread of its existing process. The settings APK declares no
service, boot receiver, alarm receiver, or store/broker dependency.

- Static patches require no listener or periodic work.
- DST uses clock/timezone/date/display-wake broadcasts, a content observer for
  the OEM checkbox, and one delayed callback for the next seasonal transition.
  Display wake rechecks after sleep; clock changes recalculate the delay. There
  is no per-minute check, periodic shell process, internet request, wake lock,
  or new resident daemon. There are six bounded 10-second startup retries after
  a failed clock/service check; later real events allow recovery.
- Heaters retry a missing temperature/climate service every three seconds for
  at most two minutes. The attempt is recorded against the kernel boot ID before
  any request. Even when disabled, the boot is recorded so enabling later applies
  at the next boot. A longstar restart cannot reapply heat over a manual adjustment.
- Maintain Climate Sync and Automatic Driver Only share one DATC status listener (event-driven).
  Ignition and the passenger belt have no working callback on this car, so Auto
  Driver Only reads them with in-process Binder getters once a second (the belt only
  while the car is on and not yet buckled). One `dumpsys com.hkmc.micom.datc` read
  seeds the climate state at connect.
- Auto-Open Phone Projection uses the native AA connection Binder callback. OEM
  ordered broadcasts skip receivers hosted by standalone root processes on this
  firmware, even when registration succeeds. While enabled, an in-process status
  check every two seconds also detects CarPlay, missed callbacks and late native
  state changes. No shell polling, new service or wake lock is involved. Seen
  connections are persisted per boot to avoid reopening after a service restart;
  camera screens defer opening. Disabling unregisters the callback and stops checks.
- Charge-Away uses the GPS fix already maintained by the head unit, checked every
  ten seconds only while enabled with Home set. A lazily created worker thread in
  the same process isolates fresh charging-feedback waits from climate/DST and
  settings saves. No new Android or NEMESIS service or GPS listener is registered.
  See [projection and Charge-Away implementation](PROJECTION-CHARGE-AWAY.md).
- The steering-wheel ★ hold: Tilepad's manifest `StarHoldReceiver` (priority 1000)
  aborts stock's long-press broadcast and forwards it only to NEMESIS Manager. A
  receiver registered by the longstar daemon can't do this: Android skips ordered
  broadcasts to it ("process crashing"), since it isn't an attached app.
- A kernel file observer reloads atomically saved configuration. Saving settings
  runs a short root command through NEMESIS; it exits when done.

This trades a small amount of in-process framework/observer state for avoiding a
second daemon and minute-by-minute polling. Actual RAM and boot latency have not
been measured on the car. Android event delivery and root-context initialization
still need emulator/device execution; the host tests do not establish those.

## Clock and heater details

The OEM uses fixed `Mobis/UTC±HH:MM` zones, so KitKat's IANA timezone database
cannot simply determine the intended DST region. Explicit modern US/Canada and
European schedules avoid relying on the old database. Geographic exceptions
must use **No daylight saving**; the default assumption is US/Canada. These rules
are not automatically updated for future law changes. Unknown timezone formats
or a clock dated before 2020 cause a visible error rather than a guessed write.
Disabling restores the original OEM flag and checkbox where they still match
Tilepad's last applied state.

The heater threshold is stored in tenths of a degree Celsius. °F/°C is a display
choice; decoded sensor readings retain fractional precision. Both raw and decoded
DATC fields must be valid. Raw zero, `ff`, `ffff`, absent fields and unreasonable
temperatures are rejected. Temperature equal to the threshold does not trigger.
Disabling automation does not switch off heaters that are already running.

The verified commands are `IUSMService` 213/214 for driver/passenger seats and
`IVRService` 16 for the actual steering-wheel actuator. Seat request values are
6/7/8 for Low/Medium/High, not literal 1/2/3. An accepted Binder request is reported
as a request; the app does not claim it proves physical heating.

## Climate tweak details

All mappings below were read from the captured firmware and confirmed on the car
on 2026-11-04.

| What | Call | Values |
| --- | --- | --- |
| Passenger belt | `tm_hscan_service` `ITM_HSCANService.getAsstStBltSwSta()`, transaction 0xcd | 1 = buckled; 0 while ignition is off |
| Driver belt | `getDrvStBltSwSta()`, 0xd1 | 1 = buckled |
| Seat occupancy | `getPassStOccSta()` 0xce, `getDrvStOccSta()` 0xd2 | always 0 on this car: the weight sensor doesn't reach the head unit |
| Ignition | `com.hkmc.micom.bcm` `IBCMService.getIGNStatus()`, 0x11 | nonzero = on; ACC reads off. A car off logs `SYS_IGN_STATUS_M: 0` and `ACTION_ACC_STATUS_CHANGED` (power status 2, ADM) |
| Gear | `tm_hscan_service` `getGearPosition()`, 0x78 (EV: `CF_Vcu_GarSelDisp`) | P 0, B 1, D 5, N 6, R 7 per `GEAR_SEL_DISP`; P = 0 seen on the car (not used) |
| Sync | `com.hkmc.micom.datc` `IDATCService.setFrontClimateControl(22, v)`, transaction 65 | 2 = on, 1 = off (the stock SYNC button's values) |
| Driver only | `setFrontClimateControl(24, v)` | 1 = on, 0 = off; leaving Driver Only restores Sync |
| Driver temp step | `setFrontClimateControl(50, v)` (`TEMP_DR_UPDN`) | 1 = up, 2 = down, one step like the stock buttons; passenger is 49 |
| Status | `IDATCService.registerDATCListener(listener, token)` 1 → `IDATCListener.onDatcStatus(DatcStatus)` 3 | `getSyncDisp()` 1 = sync, `getDriverOnly()`, `getOperationStatus()` 1 = climate on, `getDrTempC()`/`getPsTempC()` |

The physical passenger temperature buttons are not head-unit input: no key event
or head-unit request is produced. The DATC changes the passenger set point,
reports it, then leaves sync. Maintain Climate Sync compares the passenger set point with its
value in the last Sync/Driver Only state. If it moved, the driver is stepped toward
it one step at a time (one in flight; a step unseen after 1 s counts as lost), and
once following it keeps following even if the passenger returns to where it began.
Sync is requested once the passenger side has
been still for 700 ms (Sync copies the driver over the passenger, so syncing
mid-burst would undo a later press). Sync turned off by hand (it drops with both
sides already matched and no passenger change) pauses the tweak until Sync is on
again or the head unit restarts. Driver Only is left alone. A driver
change that isn't one of the tweak's own pending steps (counted per direction, so a
late step after a reversal isn't mistaken for a press) is
the driver's choice: the catch-up stops and Sync is requested, so the passenger
takes the driver's new setting. The
temperature/status pair settles for 40 ms; a request that changes nothing is retried twice, and at most
40 driver steps are sent per change.

Automatic Driver Only works per drive, from ignition on to ignition off
(`getIGNStatus`): a short stop doesn't restart the head unit, so a boot isn't a
drive. Its stage (Driver Only set, Sync set) is saved with the boot ID, so a
longstar restart mid-drive doesn't repeat a step, and reset to new as soon as the
ignition reads off. It acts only while the ignition is on, and waits for the
climate to be on.

The ★ hold: at ~800 ms the MICOM marks the press long (`flags=0x80`) and
`ModeManagerService` sends the ordered broadcast
`com.mobis.intent.action.SWRC_CUSTOM_SHORTCUT`. Its receivers are ButtonSettings'
`HardKeyReceiver` (opens Button Settings), VoiceMemo, and NEMESIS Manager's
`SetupReceiver` (press-count trigger). A short release sends the assigned action
(here `CONNECTIVITY_SHORTCUT`) instead, which is left alone.

## Compatibility and ownership

First-draft vehicle support is the captured IONIQ 5:
`wp_daudioplus_neev23_us`, firmware `NEEV23.USA.0000.V015.012.260507`.
Vehicle writes require API 19, that device ID, and the exact captured SHA-256 of
both `automotive.odex` and `automotive-service.odex`. Interface descriptors and
reply shapes are checked before accepting a result. No OEM framework is bundled.

On other firmware, an unavailable tweak shows **Save Compatibility Report to USB**. The root
`report` command writes `tilepad-compat-<ro.build.product>.txt` to the first USB drive
(`/mnt/media_rw/usbN`, written as media_rw, uid 1023): the build properties, the SHA-256 of
every gated file (the vehicle and projection frameworks, the EV Settings APK, each patch
recipe's target and paired APK, Tilepad's stock backup of any file it patched, and every
`daudio_*_base.kl` key layout), and each tweak's on/off state and unavailable reason. No OEM
file, GPS position or Home is included. The owner attaches it to a GitHub issue
(`.github/ISSUE_TEMPLATE/compatibility.md`); matching hashes mean a profile can be added
directly, differing ones mean the files need reverse engineering first.

Static patches require exact stock file hashes and exact paired APK hashes:

- Consent: `SystemUI.odex`; the startup call and the later `T(I)` construction
  entry use `dc()`, which sets the normal agreement properties and broadcast.
  The replacement preserves existing invocation addresses/register maps.
- Navigation handle: the two previously researched branches in
  `k.r.f(ZZ)Z`, byte-identical to the existing local patch when enabled alone.
- Pop-over: `k.r.canShowSSOsd(ZZZ)Z` returns false; shared `r.i.d(IIZ)V` and
  `r.i.handleShow()` return before queuing/rendering the OSD. The rest of the
  split-screen application remains installed and running. This is new work;
  all runtime effects are still subject to the car verification checklist.
- Track swap: only keys 34/35 in the captured `neev23_base` and
  `neev23se_base` layouts. Other keys, comments and whitespace are retained.
  The active hardware layout and the resulting direction need a car check.

State, original bytes and per-file journals are under the root-only directory
`/data/nemesis/backup/tilepad-tweaks`, outside the replaceable package directory.
Configuration is validated Properties data, never sourced as shell. Settings
changes are serialized by a file lock. Per-file write-ahead hashes allow resuming
an interrupted process; pending configuration commits either complete or roll
back to the last committed settings. Unsupported/conflicting files are never
silently adopted or overwritten.

The two right-panel switches build their combined result from **one** verified
stock backup. Disabling either preserves the other. Turning off all file tweaks
restores exact original bytes. Updates preserve configuration and backups;
uninstall restores owned tweaks and DST before removing either APK. A failed
restore retains the recovery runtime. Backups are retained even after successful
restoration, so a later conflict does not destroy the recovery record. These
checks address interrupted processes, not a proven power-loss transaction across
all files; power-loss behavior has not been tested.

Disable equivalent Cabinwright/other patches before enabling the corresponding
Tilepad switch. Existing foreign modifications show as unavailable. Keep the USB
uninstall bundle, which includes a signed runtime and patch recipes to restore
owned changes even when Tilepad's package directory is missing. Its
`tilepad/restore-tweaks.sh` can restore tweaks without removing the launcher;
NEMESIS can run it as a root execute script. It does not reboot automatically.

An explicit one-time tool can adopt the exact known legacy handle-only patch
without rewriting its active code. This was completed on the owner's car on
November 4, preserving the user's other settings. It verifies the complete
stock-to-patched relationship and paired APK before creating ownership records;
general foreign patches remain refused. See
[the migration notes](../tools/tweaks/migration/README.md).

## Build and validation

Normal release pipeline (both APKs, unit tests, lint, signing and bundles):

```sh
sh tools/build_apk.sh
python3 tools/build_usb_bundles.py
```

Restricted-host fallback (direct SDK compilation against API 19; does not run
Gradle lint automatically):

```sh
sh tools/build_apk.sh --sdk-only
```

It requires a cached API 19 `android.jar` or `TILEPAD_API19_JAR`. Both APKs use the
existing pinned Tilepad signing key, SHA-1 v1 signatures, aligned resources and
DEX 035. The bundles include both APKs, recipes and the upstream MIT notice.

```sh
sh tools/tweaks/test-host.sh
python3 tools/tweaks/verify_capture.py /path/to/captured/system
```

Host coverage includes DST boundaries/offsets, sensor validity, configuration
validation, launcher migration, composable patches, foreign-file rejection,
process interruption, rollback, paired APK install/update/uninstall and exact
restoration. Capture verification checks all sixteen static-switch combinations
on real captured files and independently disassembles the modified ODEX.

Emulator UI preview, from a host with ADB access:

```sh
./gradlew :app:assembleDebug :tweaks:assembleDebug
adb -s emulator-5580 install -r tweaks/build/outputs/apk/debug/tweaks-debug.apk
adb -s emulator-5580 shell am start -n com.tilepad.tweaks/.TweaksActivity --ez design_preview true
```

Preview is available only in a debuggable build on ranchu/goldfish. Its banner
identifies simulated settings and it never calls the vehicle/root backend.
The captured theme image and mock services are described in the sibling
`gen5-RearDashcam/tools/emulator-theme/README.md`. That emulator approximates OEM
theme behavior; it does not reproduce the full OEM window manager or actuators.

The implementation/test state for this branch is recorded in
[TWEAKS-MISSION.md](TWEAKS-MISSION.md); the remaining checks are in
[TWEAKS-DEVICE-CHECKLIST.md](TWEAKS-DEVICE-CHECKLIST.md).

## Sources and attribution

- [Cabinwright tweaks](https://github.com/cabinwright/tweaks): consent and handle
  patch research; MIT notice shipped in `tune-service/notices/Cabinwright-MIT.txt`.
- Local `gen5-disable-nav-overlay` research: verified handle controller branches.
- [Auto-DST-Gen5W](https://github.com/ntwm787/Auto-DST-Gen5W): OEM clock-service
  behavior reference; this implementation replaces its periodic shell loop with
  event-driven scheduling inside Tilepad.
- [hkmc-coldstart-heat](https://github.com/eclipseempire/hkmc-coldstart-heat) and its
  [extension notes](https://github.com/eclipseempire/hkmc-coldstart-heat/blob/main/EXTENDING.md):
  actuator and enum references, cross-checked against the captured Binder catalog.
- [hkmc-track-button-swap](https://github.com/dibble9012/hkmc-track-button-swap):
  mapping reference; Tilepad uses its own verified backup/restore implementation.
- Owner's captured firmware: exact files, service interfaces, stock display
  setting explanation and the newly researched shared OSD paths.
