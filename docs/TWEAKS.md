# Bundled Tweaks

`Tweaks.apk` (`com.tilepad.tweaks`) is a separate settings app installed with
Tilepad. The permanent **Tweaks and Settings** tile occupies the lower-left corner of the fixed
core block on page one, with Precondition and Reboot Head Unit to its right; Wi-Fi,
ADB and Nemesis Manager occupy the upper row. A thin vertical divider separates the
block from the user's tiles, which continue on further swipeable pages when they
don't fit. Existing user-tile order and shortcuts are retained.

The Tweaks and Settings tile uses the same status-card format as the other three: glyph,
status pill, caption, headline, detail and hint. Any enabled tweak gives an **On**
status; none gives **Off**. The detail shows the enabled count out of the total.
The count is read when the launcher resumes through the lightweight root
`summary` command; it does not hash OEM APKs, access vehicle services, poll while
the launcher is closed, or claim that unknown settings are off. Counts refer to
enabled selections, including any file changes waiting for a restart.

All eleven tweaks start **off**. Under each checkbox a sentence or two describes the current state;
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
| Skip Startup Safety Brief | Uses the OEM agreement path instead of building the consent screen at boot; every later request (driver picker, profile password, post-update notice) still opens it | None | Yes |
| Automatic Heaters | One request to selected heaters each time the car is turned on in the cold; the passenger seat waits for the front passenger's belt (polled once a second until the car is turned off); never continuously overrides manual settings | Threshold, °F/°C, driver/wheel/passenger checkboxes, independent seat levels 1–3 | Next car start |
| Swap Steering-Wheel Track Buttons | Reverses keys 34/35; up advances, down goes back | None | Yes |
| Hide Right-Hand Nav Handle | Forces the stock handle controller's hidden path, and keeps Split Screen off (the handle is its only off switch); shown under the pop-over entry and always on while the pop-over is disabled | None | Yes |
| Disable Right-Hand Pop-Over | Blocks the shared right-side OSD request path and renderer | None | Yes |
| Maintain Climate Sync | A passenger temperature change moves the driver temperature to match, then turns Sync back on | None | No |
| Automatic Driver Only | Driver Only every time the car is turned on; Sync once the passenger belt is buckled (one way, until the next car start) | None | No |
| Auto-Open Phone Projection | Opens AA/CarPlay once when a session becomes available, independently of the right pop-over | None | No |
| Charge-Away | Only on cars that can be plugged in (HEVService's EV or PHEV feature; hidden on gas cars and plain hybrids). Scheduled Charging off beyond 1.5 miles of Home, on within 1 mile; retains state between boundaries and preserves the off-peak-only choice | Set Home from the current GPS location, update it, or clear it; coordinates shown | No |
| Disable OTA Client | Disables the head unit's OTA client, stopping its checks, downloads and prompts; turning off or uninstalling Tilepad reenables it, while updates preserve the setting | None | No |

**Disable OTA Client** uses Android's persistent package switch for
`com.hkmc.system.app.OTAClient`, with an exact-package read-back after each
change. No system file is modified and no watchdog is added. A recovery record
is saved before disabling; the existing transaction journal covers interruption
and rollback. Restore, including the USB recovery runtime, reenables the client
before retiring that record. If reenablement fails, uninstall stops and retains
the recovery files. Enabling the tweak takes ownership even if the client was
already disabled, so turning it off or uninstalling explicitly reenables it.
An untouched, off-by-default tweak leaves package state alone. NEMESIS updates
skip restore and keep the setting and record outside the replaceable package.

This uses standard Android package management and checks that the exact package
is installed; it does not depend on an OEM file hash or vehicle-service API.
Diagnostic status also checks that a selected OTA block is still in effect.
It does not block an update already staged for a separate agent, modem updates,
or vehicle updates handled elsewhere. Local update/recovery components remain
installed.

Compatibility: across Hyundai USA V015.012.260507,
Hyundai USA V016.010.260903 and Kia USA V015.012.260507, the archived `pm`
methods `runSetEnabledSetting`, `runListPackages` and `runPath`, plus
PackageManagerService's `setApplicationEnabledSetting` and `setEnabledSetting`,
normalize identically: five methods checked, zero differences across the three
images. The captured V015 client has the exact package identity above. The
V016/Kia compatibility logs do not contain the OTA APK, so its presence is
checked on the head unit rather than inferred from those logs. Future firmware
with that same package can use the tweak without an allowlist change; a missing
package or failed read-back is refused.

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
- Heaters read the ignition with an in-process Binder getter every two seconds and
  start once per car start (off to on), since turning the car off and on often
  leaves the head unit running, and the head unit can boot before the car is on.
  They then retry a missing temperature/climate service every three seconds for at
  most two minutes; turning the car off ends the wait. The car start is recorded
  (`heaters.drive`, boot ID plus ignition) before any request. Even when disabled,
  it is recorded so enabling later applies at the next car start. A longstar
  restart cannot reapply heat over a manual adjustment. A timeout status quotes the
  last raw and decoded outdoor-temperature values the climate service reported.
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
  See [Projection and Charge-Away details](#projection-and-charge-away-details).
- The steering-wheel ★ hold: the longstar daemon times the hold from the key device. A
  hold makes at most three Binder calls to the mode service (read ★'s action, then
  block it and allow it again), and only the first with ★ set to None. With None, the
  car sends its long-press broadcast at the hold; Tilepad's manifest `StarHoldReceiver`
  (priority 1000) aborts it and forwards it only to NEMESIS Manager. A receiver
  registered by the longstar daemon can't do this: Android skips ordered broadcasts to
  it ("process crashing"), since it isn't an attached app.
- A kernel file observer reloads atomically saved configuration. Saving settings
  runs a short root command through NEMESIS; it exits when done.

This trades a small amount of in-process framework/observer state for avoiding a
second daemon and minute-by-minute polling.

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

The ★ hold: at ~800 ms the MICOM marks the press long (`flags=0x80`). With ★ set to
None in Button settings, `ModeManagerService` then sends the ordered broadcast
`com.mobis.intent.action.SWRC_CUSTOM_SHORTCUT`, and only with None. Its receivers are
ButtonSettings' `HardKeyReceiver` (opens Button Settings), VoiceMemo, and NEMESIS
Manager's `SetupReceiver` (press-count trigger). With ★ set to an action (here
`CONNECTIVITY_SHORTCUT`), the car runs that action on the release instead, after a
short press and after a hold alike. A short press is left alone; during a hold the
longstar daemon blocks the action (see TECHNICAL.md).

## Projection and Charge-Away details

Setting, updating or clearing Home is a separate explicit action (clearing asks first), shows
six-decimal latitude/longitude, and saves both coordinates atomically. No map,
network geocoding, or search dependency is introduced. Home is kept in the existing
protected root configuration, not written to a shared external-storage file.

Auto-Open Phone Projection uses the native AA connection Binder callback and
checks the underlying native AA/CarPlay services every two seconds while enabled.
OEM ordered connection broadcasts cannot reach the standalone root runtime on
this firmware, so projection does not depend on its dynamically registered receivers. It uses
the stock pop-over's `GPM_CUSTOM_SHORTCUT` / `DIPO_CUSTOM_SHORTCUT` actions; the
PhoneProjection APK's launcher is connection settings, not the projected screen.
Opening is once per availability transition, including an already available
session when enabled. Turning the car on (BCM ignition off to on) counts as a new
transition, since a soft off often keeps a wireless session connected. Duplicate notifications, leaving projection manually, and
restarting longstar do not repeatedly steal focus. Seen state is persisted per
kernel boot. Camera screens defer opening; already foreground projection is left
alone. Native service availability is rechecked without inventing disconnects.
Launch failures have a six-request bound per availability edge; a new connection
or toggling the feature permits recovery. Disabling stops checks, removes the
native callback and leaves the current screen alone.

Charge-Away polls every ten seconds while enabled with Home saved. It reads the
existing GPS fix from `dumpsys location`; this root UID cannot pass the stock
LocationManager package-ownership check. It does not register another GPS listener.
Fixes must be at most 60 seconds old with accuracy at most 150 metres. The `et`
field is an absolute elapsed timestamp, not an age. Haversine distance uses
1 statute mile = 1609.344 metres. The complete accuracy radius must be outside
1.5 miles to select Away, or inside 1 mile to select Home. Between thresholds the
last region is retained across restarts, keyed to the saved Home. Unknown initial
regions and stale/missing fixes leave Scheduled Charging unchanged.

Only the Scheduled Charging master state is controlled. The native 12-field
feedback contains nine writable fields; the first eight are copied exactly and
only field 8 changes. Off is 0; enabled mode is 2 or 3 according to the stock app's
saved off-peak-only preference. That preference is read as the owning app UID from
its XML (or stock UNECE secure JSON path) and is never written. An already enabled
mode is not rewritten. Missing/unconfigured times prevent enabling; the user must
set their times and off-peak-only choice in EV Settings. Departure and climate
schedules are not touched.

The cached economic-charge getter is deliberately not used as write authority.
Each read registers a short-lived IHEVListener, requests native feedback, waits up
to 1.5 seconds, and unregisters in all cases. Immediately before writing, another
fresh response must match all nine previously read writable fields. Feedback
waits run on a lazy worker thread inside longstar, outside the shared settings
lock. Only the final command is serialized with config changes and rechecks that
the same Home and enablement are still selected. There is no new registered
Android/NEMESIS service, alarm, boot receiver, or external daemon.

Writes occur only when the desired master state differs. Up to three unconfirmed
requests are allowed before pausing until state, Home, selection, or runtime
restart changes. A valid later poll enforces the region state even after a manual
master toggle. Turning the tweak off stops its polling and leaves the current
Scheduled Charging state alone; Home remains saved. In-app help explains this.

The native AA callback is a `com.hkmc.gpm.IGpmListener` registered with `IGpmInterfaces`
transaction 9 (remove: 10); callback 1 is `onConnectStatus(int)` and 2 is `onServiceKill(boolean)`.
Callbacks post back to the runtime handler before querying any native service, avoiding re-entry
while an OEM callback thread may hold its service lock. OEM ordered broadcasts (`gpm.set`,
`UPDATE_ANDROIDAUTO`) skip receivers hosted by the root runtime ("process crashing": the receiver
has no attached app), which is why the callback and the status check are used instead. Status says
opening was requested, rather than treating a sent broadcast as proof the projected screen is
visible.

The OEM interfaces involved, as read from the stock EV Settings save method and the IHEVService
proxy:

| Interface | Verified mapping |
| --- | --- |
| `gpm`, `com.hkmc.gpm.IGpmInterfaces` | transaction 1; status >=4 means connected |
| `dipo`, `android.dipo.IDiPOManager` | transaction 8 (`getDiPOConnectStatus`); 4 means a CarPlay session is activated. Transaction 2 (`getCurrentDiPOStat`) only holds 0 stopped, 1 started, 2 starting |
| `com.hkmc.micom.hev`, `com.hkmc.hev.IHEVService` | 1/2 add/remove listener, 14 request feedback, 30 set nine fields |
| `com.hkmc.hev.IHEVListener` | callback 4 provides 12 economic-charge fields |
| Stock preference | `economic_only_economic_mode`: 0 prefer, 1 only off-peak |

Firmware without matching vehicle, framework and EV Settings hashes is refused. No OEM binary or
GPS data is shipped.

## Compatibility and ownership

Support is keyed to firmware files, not the car's name. One `/system` image serves several models
(the unit sets `ro.product.device`, e.g. `wp_daudioplus_neev23_us`, at boot), so cars on the same
image have byte-identical files whatever model they are. Hyundai and Kia build separate images of a
release (`build.prop` names them `ECOHMC` and `ECOKMC`), so each is listed on its own. Supported
images:

| Image | First seen | How it was checked |
|---|---|---|
| Hyundai V015.012.260507 | the IONIQ 5 capture | captured `/system`, all patches verified on the car |
| Hyundai V016.010.260903 | a compatibility log | every interface Tilepad calls compared with V015's (below) |
| Kia V015.012.260507 | an EV6 compatibility log | every deodexed file compared with Hyundai V015's (below) |
| Hyundai Canada V015.011.260206 | a Canadian IONIQ 5 compatibility log | compared with Hyundai V015.012's: vehicle code byte-identical, patched classes identical (below) |
| Kia Canada V015.011.260206 | a Canadian EV6 compatibility log | deodexes byte-identically to Kia V015.012; SystemUI.apk differs (below) |
| Hyundai V015.012.260507 DN8 | a Sonata compatibility log | Hyundai's non-EV image: every call Tilepad makes compared with Hyundai and Kia V015; consent re-anchored (below) |
| Kia V015.012.260507 KIA | Sorento and Niro EV compatibility logs | shared `KIA.USA` image compared with the supported Sonata image; every transaction entry and patched class matches (below) |
| Hyundai V015.012.260507 CE | an IONIQ 6 compatibility log | Hyundai's `ECOHMCSE` image: vehicle and projection files are V015's apart from build time; patched methods identical (below) |
| Hyundai V016.010.260903 CE | an IONIQ 6 compatibility log | vehicle and projection code identical to Hyundai V016's; the apps differ from it as CE V015's do from V015 (below) |
| Hyundai V012.010.240822 | an IONIQ 5 compatibility log | every call Tilepad makes compared with V015's; heater codes per release (below) |
| Hyundai V014.010.250818 | an IONIQ 5 compatibility log | every call Tilepad makes compared with V012's and V015's: V012's heater codes, V015's key-action codes (below) |
| Hyundai V016.010.260903 HYUNDAI | a Palisade compatibility log | Hyundai's non-EV image at V016: code identical to Hyundai V016's apart from `MobisBuildFeature`'s flags; V016's recipes (below) |

`Firmware.java` lists each release's `automotive.odex`/`automotive-service.odex` (vehicle tweaks),
`framework.odex`/`framework2.odex` (projection) and `HKMC_TAppEV3.apk` (Charge-Away) hashes; a pair
must come from one image. Interface descriptors and reply shapes are still checked before
accepting a result. No OEM framework is bundled.

Adding a release from its log: the `tilepad-compat` skill (`.claude/skills/`) runs these steps with
the scripts in `tools/compat/` (triage, deodex, compare, transaction tables, patch check, and
`corpus.py` to run them across every archived log). Deodex both
releases and compare the binder stubs and proxies of
every interface Tilepad calls (IUSMService, IVRService, IDATCService/IDATCListener/DatcStatus,
IBCMService, ITM_HSCANService, ITimePolicyService, IHEVService/IHEVListener, IGpmInterfaces/
IGpmListener, IDiPOManager, `com.hkmc.Feature`), the service implementations behind them, and the EV
Settings app's schedule path (it is obfuscated, so compare with renamed members masked). For V016:
all stubs were identical except HSCAN, which only gained 0xe3-0xe8; DATCService only fixed a string;
HEVService only changed `setTargetSOC` (0x29, unused); EV Settings differed only by renames. Then run
`tools/tweaks/firmware_patches.py <log>/system <release> --compat-log <log>/tilepad-compat.txt` for
the file patches, and check the patched methods match the previous release's with members masked.

Deodex with `-a 18`, not 19: baksmali 2.5.2's API 19 field layout doesn't match this Dalvik, so
thousands of quickened field accesses fail to resolve (it prints `AnalysisException` and replaces
them with `throw`). With 18 every file deodexes cleanly.

For Kia V015 (the EV6 log): the two images are one source tree built four minutes apart.
`automotive`, `automotive-service`, `framework`, `framework2`, `services`, `com.infobank`,
`com.hkmc.ext.hcan`, SystemUI, RightScreenHome and EV Settings are identical once line numbers
and resource IDs are masked; Kia's framework only adds four SOS/ACN resources, which shifts the IDs.
The generated `consent-kia-v015`/`right-kia-v015` recipes have exactly V015's offsets and bytes, and
every patched method's code, header included, is byte-identical to V015's at the same offset. Its
DATC reports the IONIQ 5's climate variant and options (0x02; 0x29, 0x04, 0x2f, 0x03, 0x01), and its
key layout is the same file, with the track keys at 34/35 and ★ at 43.

For Hyundai Canada V015.011.260206 (a 2024 IONIQ 5 log; the car reports `NEEV.CAN`, and the
image's own `build.prop` labels it `ECOHMC.USA.0000.V015.011.260206`), an earlier V015 than the
capture's: compared with Hyundai V015.012. `automotive`, `automotive-service`, `framework`, `framework2`,
`services`, `com.hkmc.ext.hcan` and `com.infobank` deodex byte-identically, so every interface and
transaction code Tilepad calls is unchanged. SystemUI, RightScreenHome and EV Settings differ only
in obfuscation: compared method by method with short names masked, every changed file has the same
members in another order, or is one of seven pairs of classes whose names swapped. None of the
changed classes is patched, or owns EV Settings' off-peak-only preference. `consent-v015-011` and
`right-v015-011` have V015's names, offsets and bytes, and the patched classes of both apps
disassemble identically to V015.012's. The climate variant and options are the IONIQ 5's, and the
key layout has the track keys at 34/35 and ★ at 43.

For Kia Canada V015.011.260206 (a 2024 EV6 log; the car reports `CVEV.CAN`, and `build.prop` labels
the image `ECOKMC.USA.0000.V015.011.260206`): compared with Kia V015.012. Every target, the vehicle
and framework files, `services`, SystemUI, RightScreenHome and EV Settings, deodexes byte-identically.
`HKMC_TAppEV3.apk`, `SystemUI.odex` and `RightScreenHome.odex` are V015.012's own files; only the four
framework ODEX files, which are this build's, and `SystemUI.apk`, which holds resources only, differ. So
`consent-kia-v015-011` is V015.012's patch paired with this APK. Since both recipes are for the same
ODEX, PatchManager picks the one whose paired APK is the car's. The climate variant and options are
the IONIQ 5's and EV6's, and the key layout has the track keys at 34/35 and ★ at 43.

For Hyundai V015.012.260507 DN8 (a 2022 US Sonata log, `wp_daudioplus_dn822_us`; `build.prop` names
the image `HYUNDAI.USA.0000.V015.012.260507`, Hyundai's build for its non-EV models): compared with
Kia's `KIA.USA` image of the same release (a Sorento log, also shared by the Niro EV) and with Hyundai
and Kia V015.012. Against `KIA.USA`, `automotive`, `automotive-service`, `services`, `hcan` and
`infobank` have no code differences, `framework` and `framework2` differ only in resource IDs and
`MobisBuildFeature`'s brand constants, and SystemUI's consent controller is the same class. Against
the EV images, `automotive` differs only in `UsageLogID`'s log constants, and `framework`,
`framework2` and `services` in Wi-Fi, media, location, Bluetooth, tethering, rear camera and HDMI
code, none of it on a path Tilepad uses (the same comparison as for Kia's non-EV image). Every
transaction code Tilepad calls is unchanged. SystemUI's consent controller is `E` with seven
registers, so `consent-dn8-v015` calls `invoke-direct {v6}, E->dc()V` at its own anchor; RightScreenHome
keeps V015's names and bytes, and its patched methods are V015's (the class's other differences,
renamed callees and resource IDs, are elsewhere). The DATC reports other options (0x09, 0x00, 0x2b),
so Climate Sync and Driver Only stay unavailable, and it can't be plugged in, so Charge-Away is
hidden. Its layout has the track keys at 34/35 but no ★: its programmable steering-wheel key is
`SHORTCUT_CUSTOM` at scan code 4, which the ★ hold uses (`KEY_CODE=auto`). ModeKeyDispatcher runs
its assigned action (e.g. "gohome") on the release, as it does the ★'s, so a hold blocks it the same
way, through key type 3 and its own block list (`processCustomKey`); see TECHNICAL.md.

For Kia V015.012.260507 KIA (issues #20 and #41: US Sorento MQ422 and 2022 Niro EV DE22EV),
`build.prop` labels the shared image `KIA.USA.0000.V015.012.260507`, distinct from the EV6's
`ECOKMC` build. Compared with the supported Sonata image: `automotive`, `automotive-service`,
`services`, `hcan` and `infobank` have zero normalized code differences; the framework differences
are resource tables/IDs and `MobisBuildFeature` brand constants. All 1,555 transaction entries match.
EV Settings' 353 classes match with short names masked, fields and methods sorted, and two pairs
of renamed classes mapped (`drivemode/n`/`o` and `ui/ev_settings/g`/`h`). The generated
`consent-kia-base-v015` and `right-kia-base-v015` recipes use the Sonata's anchors and names,
including consent controller `E` and `this` register v6. Every consent/nav/popover combination
applies, and the all-off configuration preserves stock bytes. The patched classes deodex with zero
code differences after resource IDs and short names are masked. Patch checks resolve quickened
calls against each image's dependencies,
since renamed methods reorder vtables; they also check the release's actual consent class.

The Niro EV's DATC reports variant 0x00 and options 0x01, 0x00, 0x0a, 0xff, 0xff, so Climate Sync
and Driver Only stay gated. Its layout has track keys 34/35 and no wheel star: `KEY_CODE=auto`
selects `SHORTCUT_CUSTOM` at scan code 20. The older log has no input-device inventory, so live
key-device discovery still needs confirmation on the car. Its OTA client was not found;
preconditioning remains outside this compatibility patch.

For Hyundai V015.012.260507 CE (issue #45, a 2023 US IONIQ 6, `wp_daudioplus_ceev_us`;
`build.prop` names the image `ECOHMCSE.USA.0000.V015.012.260507`, a separate Hyundai EV build of the
IONIQ 5's release, built the same day): compared with Hyundai V015.012. `automotive`,
`automotive-service`, `framework` and `framework2` differ only in build time (`odex_same.py`: same
embedded DEX, optimized data and dependencies; only the source jar's modification time and the
checksum over it differ), and `services`, `hcan` and `infobank` deodex with no code differences, so
every interface and transaction code Tilepad calls is unchanged. The apps are the IONIQ 6's own: with
short names masked and members sorted, EV Settings differs in four classes (a QR encoder, two drive
mode classes and an energy information view), none of them the off-peak preference or charge
schedule. `consent-ce-v015` has V015's offsets and bytes. `right-ce-v015` has V015's nav and first
pop-over entries; the OSD method sits 2,300 bytes earlier and is otherwise the same. Patched, the
consent class and OSD class disassemble identically to V015's, and the nav class differs only in
three renamed callees (`ContentLayout` and `n/v`), whose bodies match apart from renamed fields, none
of them in the patched `f(ZZ)Z`. The DATC reports the IONIQ 5's variant and options, so Climate Sync
and Driver Only apply, and the key layout is the IONIQ 5's file: track keys at 34/35, ★ at 43.

For Hyundai V016.010.260903 CE (issue #47, a 2024 US IONIQ 6, `wp_daudioplus_ceev_us`; `build.prop`
names the image `ECOHMCSE.USA.0000.V016.010.260903`): compared with Hyundai V016. `automotive`,
`automotive-service`, `framework`, `framework2`, `services`, `hcan` and `infobank` deodex
byte-identically, so every interface and transaction code Tilepad calls is V016's. The apps differ
from V016's as CE V015's differ from V015's: with short names masked and members sorted, the same 16
RightScreenHome and 12 SystemUI classes differ, and in EV Settings three of the same four (the QR
encoder and drive mode classes) plus a charging-fault pop-up whose only difference is renamed fields. `consent-ce-v016` has V016's offsets and bytes;
`right-ce-v016` has V016's nav and first pop-over entries, with the OSD method 2,300 bytes earlier.
Patched, the consent and OSD classes disassemble identically to V016's, and the nav class differs
only in the renamed callees its stock copy already had. The climate variant and options are the
IONIQ 5's, and the key layout has the track keys at 34/35 and ★ at 43.

For Hyundai V016.010.260903 HYUNDAI (issue #54, a 2024 US Palisade, `wp_daudioplus_lx2pe_us`;
`build.prop` names the image `HYUNDAI.USA.0000.V016.010.260903`, the DN8 Sonata's image at V016):
compared with Hyundai V016. Every deodexed file has the same code, with names masked or not, apart
from framework2's `MobisBuildFeature`, whose `USE_NIGHT_ONLY`, `USE_ECOGUI_COMMON` and
`USE_NEW_GUI_4TH_HYUNDAI` flags are set as on the DN8's V015 image. All 1,561 transaction entries
match V016's. Unlike the DN8's V015 image, its SystemUI and RightScreenHome are V016's code, so
`consent-base-v016` and `right-base-v016` have V016's offsets and bytes, and patched, every method
disassembles identically to V016's. The DATC reports another climate (Variant 0x2c; 0x89 0x04 0x2b
0x08 0x00, with rear controls), so Climate Sync and Driver Only stay unavailable. The key layout has
the track keys at 34/35 and ★ at 43.

For Hyundai V012 (an IONIQ 5 log, 2024): an older build, with renamed obfuscated members throughout,
so each call was compared on its own. Every code Tilepad uses on IDATCService, IBCMService,
ITM_HSCANService, IVRService, ITimePolicyService, IHEVService, IGpmInterfaces and IDiPOManager has
the same method and number, and their implementations are identical, apart from HEVService's
listener calls (fewer initial status flags, no null check on removal: Tilepad never passes null).
BCM and HSCAN only lack newer codes Tilepad doesn't call. IUSMService numbers its methods
differently: V015 inserted methods, so V012's heater codes are lower (`setFrontSeatHeatDrvStep`
0xd4, `setFrontSeatHeatAstStep` 0xd5, `FrontSeatHeat_DrvOption` 0x243, `FrontSeatHeat_AstOption`
0x244, `SWRC_SteeringwheelHeatingOpt` 0x23f). V015's driver-seat code is V012's passenger seat, so
`Firmware.java` carries each release's heater codes. The methods behind them are the same, except
the getters read their internal status index one lower. `DatcStatus`, the DATC dump, the EV
Settings off-peak preference and `EconomicData` (the charge-schedule array) are identical. In the
projection frameworks, `DiPOMode` only lacks V015's parcel bounds checks. The recipes needed V012's
obfuscated names (`firmware_patches.py` `NAMES`). The nav method is one code unit shorter before
its second branch (a field write where V015 calls a method), and the consent screen's `start()` has
`this` in v6. Patched with Tilepad's own code, every patched method matches V015's once names,
registers and field offsets are masked; only the dead code after the OSD entry's return differs.
The DATC variant and options and the key layout match the IONIQ 5's.

For Hyundai V014.010.250818 (issue #48, a 2022 US IONIQ 5, `wp_daudioplus_neev_us`), the release
between V012 and V015: compared with both. Its IUSMService numbering is V012's (all 445 codes that
differ between V012 and V015 have V012's number), and USMService's heater methods are V012's, so it
uses V012's heater codes. IModeManager is V015's: V014 added `getSwrcCustomKeyAction`, which moved
the key-action codes up, and `getKeyAction`, `disableKeyAction` and `enableKeyAction` are identical
to V015's, so `Firmware.java` gives V014 V012's heater codes with V015's key-action codes. Every
other code Tilepad calls has the same method and number on all three releases. The implementations
behind them (VRService, TimePolicyService, ClusterService, DiPOService, the DATC, HEV and BCM calls,
HSCAN's belt reading) are V015's, except HEVService's listener registration and status parsing,
which send fewer initial status flags (0x72, between V012's 0x70 and V015's 0x74), and BCM, which
lacks V015's AMP call status. The economic-charge path (`getEconomicChargeTime`,
`setEconomicChargeTime`, the status handler that sends the schedule), `DatcStatus` and the IHEV
proxies are V015's. EV Settings is obfuscated differently, but with short names masked and members
sorted the charge schedule (`EconomicData`) and the off-peak preference code match V015's. In the
frameworks only IPPManager (renumbered; Tilepad doesn't call it), connectivity and a feature constant
differ. The recipes keep V015's names and consent anchor, but the agreement method is `cY`, and the
nav method writes a field where V015 calls a method, as V012's does, so V014 takes V012's nav offsets
(`firmware_patches.py` `NAMES`). Patched with Tilepad's own code, the five patched methods match
V015's with names masked, apart from that field write, which stock already has. The DATC variant and
options are the IONIQ 5's, and the key layout has the track keys at 34/35 and ★ at 43.

Features a trim or model can lack are checked on the car itself:

- Automatic Heaters reads the car's equipment signals before each heater (IUSMService
  `FrontSeatHeat_DrvOption`, `FrontSeatHeat_AstOption`, `SWRC_SteeringwheelHeatingOpt`: 0x245,
  0x246 and 0x241 from V015 on, 0x243, 0x244 and 0x23f on V012 and V014); a heater that reads 0 is skipped and the Tweaks screen
  says so. An unreadable signal also skips that heater.
- Maintain Climate Sync and Driver Only need dual-zone climate, so they also require a
  `wp_daudioplus_neev*` (IONIQ 5), `wp_daudioplus_cvev*` (EV6) or `wp_daudioplus_ceev*` (IONIQ 6) device until a log shows another
  model's climate.

On other firmware, an unavailable tweak shows **Capture Compatibility Log to USB**. The root
`report` command streams `tilepad-compat-<ro.build.product>.zip` (about 17 MB) to the first USB
drive (`/mnt/media_rw/usbN`, written as media_rw, uid 1023), with a spinner while it runs. It holds:

- `tilepad-compat.txt`: build and vehicle-option properties, the SHA-256 of every gated file
  (vehicle and projection frameworks, the EV Settings APK, each patch recipe's target and paired
  APK, the loaded key layouts, and Tilepad's stock backup of any file it patched), the head unit's
  key devices from `/proc/bus/input/devices` (`daudio_*` names only, since a paired phone can appear
  under its owner's name) with whether the ★ hold's `daudio_<ro.product.device>` is among them, and
  each tweak's on/off state and unavailable reason.
- The code behind those gates: every `/system/framework/*.odex` (the full boot class path, needed
  to deodex any of it), the gated apps' ODEX, the loaded key layouts, `build.prop`, and
  `tilepad-stock/` backups. APKs are hashed only; their code is in the ODEX.
- `dumps/`: the USM, BCM, DATC and HEV service dumps, numeric lines only (text values can hold
  digital-key phone names).

No GPS position, Home or personal text is included. The owner attaches the zip to a GitHub issue
(`.github/ISSUE_TEMPLATE/compatibility.md`), which is enough to add that release. Once the zip is
on the plugged-in drive, the tweak shows where it was saved and how to submit it instead of the
reason and the button.

After the tweaks, **Support** is for everything else: it shows Tilepad's version and the head
unit's software, and **Save Log to USB** runs the `support` command, which writes
`tilepad-log-<ro.build.product>-<time>.zip` for a problem report
(`.github/ISSUE_TEMPLATE/problem.md`). It holds everything above, plus `support/`, captured first so
it shows the moment the owner asked:

- `windows.txt`: `dumpsys window windows` reduced to each window's owner, type, flags, frame and
  visibility, and the focused window; then the activity stacks and resumed activity.
- `logcat.txt`: `logcat -d -v threadtime`, read as the log group (uid 1007; this root channel has
  no `CAP_SYSLOG`), keeping Tilepad's own lines, activity starts and process deaths, crashes, the
  consent screen's lines without names, and the panel processes' (RightScreenHome, SystemUI) lines
  about OSDs, views and modes. Lines about media, calls, contacts, radio, Bluetooth devices,
  location or profiles are dropped, and long digit runs masked. Its first line counts the lines
  read and kept, so a capture that couldn't read other apps' logs shows.
- `processes.txt`: each panel process's uid, start time and the code files it mapped (read as its
  own uid), with `(deleted)` when Tilepad replaced a file after the process started.
- `state.txt`: boot id and uptime, `runtime.properties`, the settings without Home, each tweak's
  status, and per patched file the recipe, stock, current and configured hashes, journal and
  modification time, with `state=as configured` or what differs.
- `tilepad-service.log`: the last 256 KB of the longstar service's log (distances from Home masked),
  and `nemesis.log`: NEMESIS's lines about Tilepad (installs, hooks, service restarts).

Tweaks' last entry, **Package Manager**, installs and updates packages straight from GitHub releases,
without a USB drive. It manages only Tilepad and the packages linked to a GitHub repository
(`update-sources.properties`, below); a package installed from NEMESIS Manager or USB and never
linked is left to NEMESIS Manager. **Check for Updates** installs newer releases of those. The app (`Updater.java`) refuses
unless Wi-Fi is on and connected and the lowest-metric default route in `/proc/net/route` is a `wlan`
interface, so nothing goes over the car's modem (`rmnet*`), and re-checks before every request. When
Wi-Fi is off or not connected, a window says so, with a button to Android's Wi-Fi settings
(`android.settings.WIFI_SETTINGS`, Hyundai's `WifiClientSettings`); on a hotspot-only unit (no
`android.hardware.wifi`) it points to Tilepad's Wi-Fi tile instead. It talks to GitHub over TLS 1.2
with AES-GCM, the only suites GitHub accepts. KitKat supports TLS 1.2 but leaves it off; stock 4.4.2
has no GCM suites at all (so the SDK emulator can't reach GitHub), while Hyundai's Conscrypt adds the
four ECDHE GCM suites as defaults, and the app switches on any supported GCM suite left off. It trusts
the system store and then `tweaks/src/main/res/raw/update_roots.pem` (NEMESIS's root set), since the
head unit's own store lacks GitHub's roots (USERTrust ECC for the API, ISRG Root X1 for downloads).
It follows HTTPS redirects only, and wants the clock set, because certificates can't be checked
before then.

For Tilepad it reads `repos/KramerTech/gen5-Tilepad/releases/latest`: the tag, the notes, and the
`tilepad.zip` and `tilepad.zip.sig` assets. **Configure Other Software** links other
packages, straight from their developers' GitHub releases, so a package needn't be in the NEMESIS
Package Store. It asks for the user or organization first (`users/<name>`), typed or picked from
the ones looked up before (root-side in `update-owners.properties`, written whole by
`update-owners NAME,…` and reported by `settings` as `owners`; KramerTech and zpoon until it's
first changed, each with a bin), then lists its repositories (`users/<name>/repos`, up to 300, most
recently pushed first). Only the ones that mention NEMESIS show at first, with **Show All N** for the
rest, which adds a search box when there are more than six. One `search/repositories` query for
`nemesis` in their names, descriptions, topics and READMEs, forks included, marks the NEMESIS ones. Reading each repository's releases instead would cost a request per
repository out of the 60 an hour GitHub allows without signing in. A pasted `owner/repo` or
github.com address skips the list. Given the repository, it reads it and its latest release in a
window of its own, which says what linking does before anything changes, shows a spinner while it
does it, and ends with what happened; Package Manager's other outcomes (an install, a removal, a
failed check) also open a window rather than a line below the buttons. It finds which
NEMESIS package each `.zip` asset holds, and links each one installed here (`packages.db`, which is
world-readable; NEMESIS 1.16.7 writes `id|name|locked|types` there, so versions come from each
package's `apps/<id>/NEMESIS.data`). A package that isn't installed is offered as a fresh
install instead: confirming links it and installs it through the same batch as an update, with plain
`nemesis install` rather than `--update`, and Check for Updates then offers it like any other (a
linked package that isn't installed shows unticked, as a new install). The locked core packages and
Tilepad itself can't be linked.
Links live root-side in `/data/nemesis/backup/tilepad/update-sources.properties` (`id=owner/repo`,
written by `update-source add|remove` and `uninstall`), so they survive Tilepad's updates; `settings` reports them as
`source.<id>`. `ZipProbe.java` reads an asset's `NEMESIS.data` with HTTP range requests (the central
directory at the end, then that entry), a few kilobytes even for SmartTube's 22 MB zips. A USB
bundle (`type=install`, `path=packages/x.zip`, like Evergreen's release) is followed to the package
inside: by range when it's stored, by streaming it from the start when it's compressed, which only
happens when the release has no plain package zip.

The pane lists Tilepad and every linked package with the version installed, read locally, under
Check for Updates and Configure Other Software side by side; the check's spinner runs inside its
button, so nothing moves. When every release matches, each version gains "(latest)". When any is
newer, every row moves over for a checkbox column: a newer one gets a checkbox (ticked, or unticked
for a new install), its installed → release versions in place of the repository, and ⓘ, which shows
the release notes without their Markdown; the rest say "Latest". The left button becomes **Install N
Updates**, whose confirmation carries the restart warning, or **Dismiss Updates** when nothing is
ticked. Configure Other Software dismisses them too, since a new link changes the list. Outside that
state each linked package has a bin: **Uninstall** runs `uninstall ID` (`nemesis uninstall`, then
the link goes; refused for Tilepad, whose USB uninstall bundle restores its tweaks first, NEMESIS's
locked packages, and while an update installs; success is read from `packages.db`), and **Unlink
Only** runs `update-source remove`, leaving the package installed.

Install downloads the ticked assets into the app's own `files/update/`, readable by root (which has
no DAC override) and writable by no other app, checks each against the SHA-256 GitHub publishes for
it (the asset's `digest`), and runs `self-update` with `ID FILE CHECK INNER` per package: CHECK is
Tilepad's signature file or the SHA-256, INNER the package's path in a bundle or `-`. That command
(`SelfUpdate.java`) copies each into `/data/nemesis/backup/tilepad/update/` (root-only; updates keep
it and uninstall removes it with the rest of Tilepad's records) and checks the copy, so the app can't
change it afterwards. Tilepad's needs a SHA256withRSA signature from the APK signing key (the
certificate is pinned in the code and matches `tools/signing-cert.sha256`; `tools/sign_update.java`
signs each release's zip in CI). Another package's must match GitHub's SHA-256, be linked, installed
and not locked, and is taken out of its bundle. Every one must carry the `id` asked for and a
`package-version` newer than the installed one; one failing check stops the whole batch before
anything installs. Linking a repository trusts it: its releases install as root with no signature
of Tilepad's, so the checksum only shows the download is the file GitHub published.

Then it writes `install.sh` there and starts it detached. The order is other packages first (one
after the packages in the batch it `depends` on), then Tilepad, then any package whose install
hooks restart the head unit themselves (Evergreen's postinstall ends with `exec /system/bin/reboot`;
`SelfUpdate.reboots` looks for a reboot command, not the word in a message), since nothing after
one would run. The script runs `nemesis install --update` for each into `<id>.log`, appends
`item.<id>.state=installed|failed` and the exit code to `result.properties`, carries on after a
failure, and restarts the head unit when any installed, which a Tilepad update needs anyway (the
in-place APK rewrite only registers at boot). Nothing it uses lives in a package directory an
install replaces, and it doesn't descend from the app, which Tilepad's install stops.

Before the first install, the script starts `InstallScreen`: a full-screen window that lists each
package as waiting, installing, installed or didn't install, until the head unit restarts, so
nobody opens apps mid-install or wonders whether anything is happening. It's a separate root
`app_process` (`tilepad-install-screen`), run from a copy of `Tilepad.apk` in `update/screen/` with
`ANDROID_DATA` pointed there, so it has its own dalvik-cache entry. A Tilepad update rewrites the
package's own APK and cache, which would otherwise be in use under it, and NEMESIS's limited root
can't create entries in `/data/dalvik-cache` anyway. The car's framework is pre-optimized
(`/system/framework/*.odex`), so nothing else lands there; on the emulator, whose framework isn't,
the first start also optimizes the framework into that folder. The window is `TYPE_SYSTEM_ALERT`:
on this firmware that's layer 17, above apps (2) and the status bar (15), below the reverse camera's
window (Hyundai's type 2035, layer 20), and Android ignores Home while it has focus. It swallows
the other keys and reads progress from `result.properties`. When nothing installed, or the head unit
hasn't restarted 90 seconds after the last install, it offers **Close**. The install never waits on it.

`settings`
reports the last batch as `update.*`: Package Manager shows "Updated … to …" for three days, or each failure
with the end of NEMESIS's output, or an install a restart interrupted (by the version now
installed, which also covers a package that restarted the head unit itself). NEMESIS removes the old
version before running the new one's preinstall, so a failed preinstall leaves the package
uninstalled; Package Manager says so. A second install is refused while one this boot is under 30 minutes
old, and staged files a restart left behind are removed. Support logs add the record and each
package's install output as `support/self-update.txt`.

Static patches require exact stock file hashes and exact paired APK hashes. Each release has its own
recipe per file (`consent-v015`, `right-v016`, …); Tilepad uses the one whose stock hash matches the
file, or its stock backup once patched. A file no recipe matches is reported as unsupported:

- Consent: `SystemUI.odex`; only `start()`'s boot call `T(0)` becomes `dc()`, which sets the
  normal agreement properties and broadcast, as factory mode and the welcome screen already do.
  The replacement preserves existing invocation addresses/register maps. `T(I)` itself is left
  alone: the same window is the driver picker. The profile app's "change driver" sends
  `SHOW_CONSENT` (`T(1)`/`T(3)`) whenever `ro.product.opt.stepofspec` is at least 20 (every
  IONIQ 5 log reports 105 or 110). A profile with a password re-locks after a car off/on
  (`ACTION_ACC_STATUS_CHANGED` → `T(5)`, only when the current driver has a 4-digit password),
  and `FORCE_SHOW_CONSENTSCREEN` (`T(4)`) follows a software update. Releases up to 1.1.8 also
  replaced `T(I)`'s entry, which left no way to switch drivers (issue #14); updating rewrites
  the file through the postinstall reconcile (below).
  That agreement goes out as SystemUI starts, which is before the HKMC services that act on it
  register for it (they do in `HkmcSystemServer.systemReady`, later in system_server's boot):
  `ThemeScheduleService` never activates, so the screen stays in day mode at night, and
  `DisplaySettingFwService`, `BluelightService`, `ModeManagerService` and `LockScreenService` miss
  it too. So while the patch is in effect (`applied.consent` and `sys.boot.confirm_screen=true`),
  the runtime sends `AGREE_USER_LICENSE` again 10 s after `sys.boot_completed`, once per boot
  (`agreement-boot` holds the boot id), as pressing Confirm would. Up to 1.1.8, the replaced
  `T(I)` sent it again whenever the system asked for the brief later, which hid this.
- Navigation handle: the two previously researched branches in
  `k.r.f(ZZ)Z`, byte-identical to the existing local patch when enabled alone.
  The handle (`SS_Button_Window`) is what turns Hyundai's split screen on and off: the panel
  (`com.mobis.splitscreen.right`, 644 px at x=1276, type 2045) sits over every app and is saved
  per driver (`split_screen_on`, `_dr1`, `_dr2` in Settings.System). With the handle hidden,
  a panel left on can't be closed (issue #13), so while the patch is in effect (`applied.nav`) the
  runtime calls the split-screen service's own `requestSplitScreenOn(false, isUser true, useAni
  false)` (`com.mobis.splitscreen.right.SplitScreenHome`, `ISplitScreenInterfaces` 0xa, after
  `isReady` 0x28), 10 s after `sys.boot_completed` and 3 s after each `USER_LOGGED_IN`. The
  interface, its codes and the method are the same on every supported image (`corpus.py stubs`,
  `corpus.py method`).
- Pop-over: `k.r.canShowSSOsd(ZZZ)Z` returns false; shared `r.i.d(IIZ)V` and
  `r.i.handleShow()` return before queuing/rendering the OSD. The rest of the
  split-screen application remains installed and running.
- Track swap: only the `key 34 CHANNEL_UP` / `key 35 CHANNEL_DOWN` lines, which all 105
  `daudio_*_base.kl` layouts in the captured image share, so the recipe names no file. It edits
  the layouts the car's `daudio_*` input devices loaded (`KeyLayoutFile` in `dumpsys input`), plus
  any layout it already backed up so disabling restores it. Each file's stock is its backup or
  the untouched file; both lines must be present once, unswapped. Other keys, comments and
  whitespace are retained.

State, original bytes and per-file journals are under the root-only directory
`/data/nemesis/backup/tilepad-tweaks`, outside the replaceable package directory.
Configuration is validated Properties data, never sourced as shell. Settings
changes are serialized by a file lock. Per-file write-ahead hashes allow resuming
an interrupted process; pending configuration commits either complete or roll
back to the last committed settings. Unsupported/conflicting files are never
silently adopted or overwritten.

The two right-panel switches build their combined result from **one** verified
stock backup. Disabling either preserves the other. Turning off all file tweaks
restores exact original bytes. Updates preserve configuration and backups, and
postinstall then runs `tweaks.sh reconcile`: a full apply of the saved settings
with the new release's recipes, so a changed recipe reaches cars that already had
the tweak on (changing a setting is otherwise the only thing that rewrites a file).
A failed reconcile changes nothing and doesn't fail the install.
Uninstall restores owned tweaks and DST and reenables the OTA client if managed
by Disable OTA Client before removing either APK. Restoring needs only Tilepad's
own backup and journal, not a recipe, so it also works after a downgrade or a
release that changed or dropped a recipe, and it covers backed-up files no recipe
names. A failed restore retains the recovery runtime. Backups are retained even after successful
restoration, so a later conflict does not destroy the recovery record. These
checks address interrupted processes, not a power-loss transaction across all files.

`scripts/remove.sh` is the whole uninstall: preuninstall runs it, and so does the
USB uninstall, after NEMESIS's own uninstall or in its place when the package is
missing (an update interrupted after NEMESIS removed the old version). It also
clears what interrupted installs and writes leave: rollback copies in
`/data/nemesis/backup/tilepad-install` (postinstall refuses to run while a failed
rollback's copies are there), `*.tilepad-new` temporary files in `/system`, and
the dex caches the restore runtime creates. Preuninstall treats a run as an update
only when the incoming package is staged and NEMESIS's command line (the hook's
parent) isn't an `uninstall` or `wipe`, so a stage left by a killed install can't
skip the restore.

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

## Pages from other packages

Another NEMESIS package can add its own page to Tweaks and Settings without any Tilepad code: it
ships a page file, `tilepad.properties`, and a command script, `bin/ctl.sh`, in its folder,
`/data/nemesis/apps/<id>/`. Its page is listed in the left pane after the tweaks, under the title
the file gives, with the file's help on the right. The page file is data only. Tilepad draws it with
its own controls, reads what it shows from system properties, and changes a setting or sends a
request only through the package's `bin/ctl.sh`. [`examples/package-page/tilepad.properties`](examples/package-page/tilepad.properties)
uses every kind of control.

Any app can write to `/data/nemesis/apps`, so Tilepad trusts a package's folder only from inside it,
in the root shell. There the folder, `bin`, `bin/ctl.sh` and the page file must each be root's, a
folder or a plain file (not a link), and writable by no one else. Otherwise nothing in the folder
runs: the page isn't listed, and if the check fails after the page was read, a change on it says so
in a window. Being inside the folder first ties the check to the folder the command then uses.
Tilepad's own folder is never read as a package page, and the design preview lists no package
pages.

**When it's read.** Each time Tweaks and Settings opens, Tilepad lists the folders that have both
files and reads each page file, as root, after the check. Until that finishes the left pane shows the
pages from the last visit. While a page is on screen, the properties it shows are read again every
2 s, without root, and the page is redrawn only if one changed. A change shows at once and is sent
in tap order on a worker of its own. If `ctl.sh` refuses it, a window says so and the page shows the
setting as it is.

**The file.** It's a Java properties file of at most 24,000 bytes, read as schema 1. Numbered
entries (`status.N`, `control.N`, `option.N`) are taken in number order. An entry Tilepad can't use
is skipped, and the page says how many were skipped. Keys it doesn't know are ignored.

| Key | What it is |
| --- | --- |
| `schema` | `1`. A page with another schema isn't shown. |
| `title` | The page's name in the left pane (required, at most 120 characters). |
| `help` | The text on the right. `\n` starts a new line, and `**words**` are bold. |
| `results` | Needed for actions: the property the package answers them in (at most 31 characters, not a `persist.` one), e.g. `exa.res`. |
| `palette.P` | Up to 64 colors, `RRGGBB` and comma-separated, for color controls (`P`: `a-z`, `0-9`, `_`, at most 16 characters). |
| `palette.P.names` | Optional: a name for each color, in the same order. |
| `presets.P` | Optional: up to 16 more colors for palette `P`, none of them already in it. The color window shows them beside the palette's colors, each named on its swatch, in columns of up to four rows. `presets.P.names` (required) names each one, and `presets.P.heading` (optional, "Presets" otherwise) heads them. A control's `default` should be one of the palette's own colors: a Tilepad without presets skips a control whose default is a preset. |
| `status.N.text` | A line at the top of the page (a template, below). `status.N.when` is its condition, and `status.N.problem=1` draws it as a problem. |
| `control.N.type` | One of the controls below. Each control may have a `when` condition. |

| Control | Keys |
| --- | --- |
| `toggle` | `prop`, `label`, `default`, `on` (`1`), `off` (`0`): a checkbox row. |
| `stepper` | `prop`, `label`, `min`, `max`, `step` (`1`), `default`, and how the number reads: `unit` (after it), `zero` (a word at `min`) or `of` (as "N / of"). |
| `temperature` | `prop`, `label`, `min`, `max`, `default`, in °C, stored with one decimal. It's shown and stepped in whole degrees of the unit that `unitprop` names: °C while that property reads `C`, otherwise °F. |
| `choices` | `prop`, `default`, `option.M=value\|label\|note` (two to 16; the note shows under the chosen one), `label` (a note above), `layout` (`vertical` or `horizontal`; without it, side by side only when every label fits). |
| `color` | `prop`, `label`, `default` (`RRGGBB`, one of a palette's colors), `palette`: a palette's name, or a template that gives one. The row shows the color and its name; a tap opens the palette's swatches, 48 dp each, 16 to a row, and its presets. |
| `action` | `label`, `request` (the verb sent to `ctl.sh req`), optional `confirm.title`, `confirm.text` and `confirm.button` to ask first, `result.WORD=text` for each answer, `timeout` (seconds, 15, at most 120). |
| `heading` | `label`: a group's name. |
| `note` | `text` (a template), and `problem=1` to draw it as a problem. |

A setting's `prop` must be a `persist.` property of at most 31 characters, and each property belongs
to one control. Until it's set, a control shows its `default`. A setting is sent to `ctl.sh` by its
`key`, which defaults to what follows the property's second word (`persist.exa.lv` is `lv`,
`persist.exa.seat.hb` is `seat.hb`); `key=` sets another, and a property without a second word
needs it.

A **condition** (`when`) is clauses joined by ` and `: `prop = v`, `prop != v`, `prop in a,b,c`,
`prop set` or `prop unset`. An unset setting counts as its default. A **template** is text with
`{prop}` places, or `{prop|value=word,…,*=word}` to show a word for a value (`*` covers the rest; a
value without a word shows as itself).

**`bin/ctl.sh`.** Tilepad runs it as root, from the package's folder, with arguments it has checked:
keys are `a-z`, `0-9`, `_` and `.`, and values are at most 64 of `A-Z`, `a-z`, `0-9` and `. _ : + -`,
always one the page allows for that control.

- `sh ./bin/ctl.sh set <key> <value>` saves a setting. Exit 0 when it's saved. Otherwise print an
  `error=` line: `bad_value`, `unknown_name` or `setprop_failed` each get their own message, and
  anything else says the command didn't finish.
- `sh ./bin/ctl.sh req <verb>` starts a request and prints `seq=<n>`, a number larger than any
  before. When it's done, the package sets its `results` property to `<verb>:<seq>:<word>`. Tilepad waits up
  to the action's timeout, then shows the action's `result.<word>` text, or the word itself. One
  request runs at a time.

A command still running after 30 s is stopped.

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

## Sources and attribution

- [Cabinwright tweaks](https://github.com/cabinwright/tweaks): consent and handle
  patch research; MIT notice shipped in `nemesis-package/notices/Cabinwright-MIT.txt`.
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
