# Bundled Tweaks mission and resume checklist

Build one standalone `Tweaks.apk` inside the Tilepad repository and distribution.
Use a stock Gen5W settings-style screen: one checkbox per tweak, explanatory help
for every feature, and a settings gear only for enabled features with options.
No Cabinwright store dependency and no second persistent background service.
Reuse Tilepad's existing TUNE service where persistent execution is necessary;
prefer static settings, reversible patches, and event-driven scheduling to polling.
All switches initially off. Preserve existing Tilepad work and user configuration.

## Requested behavior

1. **Automatic DST:** keep Hyundai's own daylight-saving setting correct with
   minimal overhead. Investigate OEM time handling before choosing an approach;
   account for boot, date/time changes and transition dates. US/Canada is the
   initial assumption pending the user's answer; explain geographic exceptions.
2. **Skip startup safety brief:** prevent the boot consent screen from being
   created, rather than detecting and dismissing it. Research Cabinwright's
   implementation; preserve and restore original state when disabled.
3. **Automatic heaters:** once per head-unit boot, below a configurable outdoor
   temperature, enable only the checked driver seat, steering wheel, and passenger
   seat. Separate driver/passenger intensity choices 1, 2, 3. Validate temperature
   units and unknown readings; do not continually override manual adjustments.
4. **Swap track buttons:** steering-wheel up advances, down goes back. Reversible
   key-layout edit, preserving unrelated keys and requiring reboot if necessary.
5. **Hide right navigation handle:** port the working local implementation of
   the hovering semicircle; restore original files on disable.
6. **Disable right pop-over:** suppress the entire transient media/info panel
   before display. Investigate stock vehicle settings and captured firmware;
   distinguish it from the navigation handle and avoid disabling unrelated UI.

## Work order / status

- [x] Inspect current Tilepad changes; create `feature/bundled-tweaks` with all
  staged, unstaged and untracked changes retained.
- [x] Write this mission before implementation.
- [x] Inspect local Cabinwright tweaks, nav-overlay research, captured firmware,
  emulator clone, and the three linked upstream repositories.
- [x] Choose shared runtime/configuration and recovery design.
- [x] Add standalone APK module and permanent Tweaks tile after Precondition;
  migrate saved tile arrangements without losing shortcuts.
- [x] Include both APKs, runtime and reversible patch payloads in install/update/
  uninstall bundles and release/build validation.
- [x] Implement DST; host-test schedules and boundary/offset cases. OEM event delivery pending.
- [x] Implement startup-screen suppression; verify captured-byte patches and disassembly. Runtime pending.
- [x] Implement configurable heaters; host-test settings, temperature validity and seat enums. Actuation pending.
- [x] Implement track-button swap; verify exact-byte changes and restoration. Hardware direction pending.
- [x] Implement navigation-handle suppression; match the previously verified local output exactly. Visual result pending.
- [x] Research and implement complete pop-over suppression; verify shared request/renderer patches. Runtime pending.
- [ ] Verify settings visuals and behavior on the available emulator.
- [x] Run available host unit, integration, packaging and restore checks.
- [x] Produce a single consolidated on-device verification checklist and record
  precisely what remains unverified.

## Resume notes

Workspace: `/home/sean/workspace`. Tilepad repo: `gen5-Tilepad`; local Cabinwright
tweaks: `tweaks`; store reference: `cabinwright-store`; navigation research:
`gen5-disable-nav-overlay`; head-unit theme tooling:
`gen5-RearDashcam/tools/emulator-theme`. No workspace AGENTS.md was found.
Existing Tilepad branch was `adb-tile`; its extensive ADB/Wi-Fi work belongs to
the user and must be preserved. Do not publish or push as part of this task.
Update this document with implementation decisions and test results as work proceeds.

## Current checkpoint (2026-11-03)

First draft of all six features exists. `tweaks/` is the independent APK;
`tweaks-core/` is shared into Tilepad's APK for root execution. No second resident
service/store is added. DST uses clock/date/timezone/wake events and its next
transition, not minute polling. Heaters get a bounded two-minute attempt per boot.
The static patch journal composes nav + pop-over from one original RightScreenHome
file and restores exact stock. Startup bypass covers both the initial call and
later `T(I)` construction requests, preserving existing call/GC-map locations.

Stock Display settings DO contain a Media change notifications checkbox, but
its own help explicitly says physical-button selections still show notices.
That setting does not satisfy complete suppression; the new patch blocks the
shared `RightSideOsd` request/renderer in addition to its permission gate.

Initial verified results:

- Both release APKs compiled directly against API 19; DEX 035 output checked.
- 74 JUnit host tests passed, including all existing launcher/backend tests,
  DST boundaries, sensor/config policy, journal interruption/rollback and migration.
- Five actual-hook filesystem-fixture tests passed: both APKs installed,
  idempotent reinstall, second-APK failure rollback, update preservation, true
  uninstall restoration and retaining recovery files on restore failure.
- All 16 static-tweak combinations passed on the actual captured files and
  restored the original bytes exactly. Modified ODEX independently disassembled.
- Direct Tweaks lint analysis: 0 errors, 5 warnings (expected hidden OEM APIs,
  fixed landscape orientation and one numeric-label localization warning).
- Direct Tilepad lint analysis: 0 errors, 28 warnings; both apps compiled against
  the actual API 19 SDK jar. Gradle's normal pipeline remains blocked as below.
- ShellCheck passed the changed lifecycle scripts at warning severity.
- Both final release APKs v1-signed with Tilepad's existing pinned certificate;
  `apksigner --min-sdk-version 19` verified both and both contain DEX 035.
- Install/update/uninstall USB bundles built. Package inspection confirmed both
  APKs match signed artifacts, exactly one resident service, no bundled OEM ODEX,
  and the standalone USB recovery runtime/recipes. `dist/SHA256SUMS` recorded.

Reviewable artifacts: `dist/Tweaks.apk`, `dist/Tilepad.apk`, `dist/tilepad.zip`,
and `dist/Tilepad-{install,update,uninstall}-usb.zip`. Changes remain local and
uncommitted on `feature/bundled-tweaks`, including the user's prior staged and
unstaged work. Nothing was pushed, published or installed on the car.

During the first implementation turn, this execution sandbox could not open ADB's
local socket or Gradle's lock/daemon socket. A network-permission request became
available and was granted on November 4; ADB now works. The emulator is the
sibling RearDashcam captured-theme ARM/API19 AVD
on port 5580. Its theme is approximate, not a full OEM window-manager clone.
Actual Android listener bootstrap, screen behavior, active key-layout selection,
heater actuation, and real boot/update/uninstall behavior remain unverified.

Resume with [TWEAKS-DEVICE-CHECKLIST.md](TWEAKS-DEVICE-CHECKLIST.md).
Implementation details and reproducible commands: [TWEAKS.md](TWEAKS.md).

## Car installation (2026-11-04)

User requested installation and enabled the head unit's ADB connection at
`192.168.0.23:5555`. Verified actual hardware `tcc803x`, API 19 and the existing
Tilepad signing certificate before updating through `nemesis install --update`.
The old package is backed up on the car at
`/data/nemesis/backup/tilepad-before-tweaks-20261104`; a host copy and old APK are
in ignored `build/car-before-tweaks/`.

Two actual-device fixes were required and included in the installed build:

- Installer staging moved to `/data/nemesis/backup/tilepad-install` because the
  limited-capability NEMESIS root shell cannot create directories in shell-owned
  `/data/local/tmp` (0771). Failed rollback copies remain durable.
- Root event registration now uses Android's standard `LoadedApk.ReceiverDispatcher`
  and `IActivityManager.registerReceiver` with a null caller ApplicationThread.
  `Context.registerReceiver` was rejected because app_process is not an attached
  ordinary app. Keeping the standard dispatcher preserves ordered-broadcast
  completion. Bootstrap passed on the emulator and the actual car.

Both APKs were installed, then the head unit was restarted to rebuild the system
APK DEX caches. The shared listener came back with the new kernel boot ID and
PID 218. The updated launcher was captured on the car showing Tweaks after
Precondition; see ignored `build/car-installed-launcher.png`. Both production
APKs were also installed on the API19 emulator, and its Tweaks screen rendered
with an explicit missing-NEMESIS explanation.

All six new switches remain off. The car already has the exact older navigation
handle patch (`RightScreenHome.odex` MD5 `e8eac4e6a0f33dc2e4cca904298a8682`).
That file was preserved. The new nav/pop-over switches currently report the
foreign modification as unavailable; migrate ownership deliberately before using
those two switches. No heater, DST or additional UI patch was enabled.

Host tests were rerun after the fixes: 74 JUnit plus five installer tests passed.
Both signed APKs and USB bundles were rebuilt; `dist/SHA256SUMS` refreshed.
Post-reboot ADB became intermittent during the final Tweaks-screen capture, so
full checkbox/settings interaction and physical feature tests remain outstanding.

## Legacy navigation cleanup completed (2026-11-04, afternoon)

User explicitly requested finishing the old navigation-overlay cleanup. ADB was
available at `192.168.0.23:5555`. At this point the user had enabled DST, heaters,
consent bypass and track swap, with driver-seat level 1; those preferences were
preserved rather than reset to the earlier defaults.

Added and tested the explicit maintenance tool in `tools/tweaks/migration/`.
It reproduces the complete current handle-only ODEX from the independently
verified stock capture and installed recipe, verifies the paired APK, holds the
normal configuration lock, and refuses all remount/system-write operations.
It adopted the existing patch into the normal backup/journal and set only
`nav=true`. `popover=false` remains unchanged. No RightScreenHome code was written,
so no reboot is required for the ownership transfer.

Actual car verification: both `unavailable.nav` and `unavailable.popover` are empty,
`runtime` is healthy and `restart=false`. Active ODEX MD5 remains
`e8eac4e6a0f33dc2e4cca904298a8682`; managed stock backup MD5 is
`a46717495f957dab68b96cf6c9a01d4a`. Migration audit is under the state directory's
`legacy-nav-migration/`. Full pre-migration state/package snapshots are at
`/data/nemesis/backup/tilepad-tweaks-before-nav-migration-20261104` and
`/data/nemesis/backup/tilepad-before-nav-cleanup-20261104`.

Also fixed whole-file hashing of paired OEM APKs: RightScreenHome.apk is 154 MiB,
so verification now hashes files through a 32 KiB buffer. Both APKs/package were
rebuilt, signed, and updated on the car through NEMESIS. The 79 existing host
tests passed; a capture-based migration check additionally covered refusal of
incorrect inputs, idempotence, preference preservation, composition and full stock
restoration after adoption. Hashing the actual 154 MiB APK passed with a 64 MiB
Java heap. The new APK signatures and refreshed USB bundles/SHA256SUMS are in dist.

After that APK update, the car reproduced the known stale-system-APK DEX error
when launching the old cached activities. Restarted the head unit; post-boot
status again confirmed `nav=true`, `popover=false`, both panel availability reasons
empty, healthy runtime and no pending file restart. Other user settings and the
warm-boot heater skip were preserved. The ownership transfer itself changed no
panel code; this restart was for loading the updated APKs. Temporary migration
input/cache files were removed, while audited backups remain intact.
The final screen capture showed the separate Digital Rear View Mirror app in
the foreground, so it was left alone rather than dismissed for a settings photo.
Panel availability/state was verified through the live root backend; a fresh
visual check of those rows in the settings screen is still separate from that.

At 15:06 the user subsequently enabled the now-unlocked pop-over switch in the
app. It was left enabled: both nav and pop-over are now selected. The resulting
on-car ODEX MD5 `3c1bbbbe56dadbce1682b57a7e15ab11` and journal SHA-256
`bc850e45ecd4ca1cdd4855b1af22c5dc0a940ccd3e751493396612f1a3af79c4`
match the tested combined-patch output exactly. This later selection is separate
from the metadata-only adoption and requires a subsequent head-unit restart to
load its newly changed panel code. Do not reset the user's selection to the
earlier migration snapshot's off state.

## Grouped launcher and Tweaks status tile (2026-11-04)

Requested by the user after navigation cleanup: match the built-in status-card
format, show a count and On when any tweak is enabled, and group the four status
tiles in a square to the left of user-managed tiles with a vertical separator.

Implemented `TweaksTile`/`TweaksSnapshot` and the root `summary` command. The
launcher refreshes on resume without full compatibility hashing or background
polling. Wi-Fi/ADB occupy the top row, Tweaks/Precondition the lower row. The
status block is fixed during right-side scrolling and drag editing; user/core
tiles on the right retain their relative saved order and shortcuts. Compact
unsupported viewports fall back to the previous grid geometry.

Validation: 80 JUnit and five hook tests pass. The API19 emulator preview shows
the new status card/count/divider; UI automation with fifteen temporary shortcuts
verified pinned status bounds during scrolling, no drag across the divider, and
no lost shortcuts. Emulator preferences were restored after this check. Evidence
is under ignored `build/grouped-preview/`. Release APKs were compiled against
API19 and v1-signed with the existing pinned key.

## EV Settings layout checkpoint — 2026-11-04

User requested the actual car EV Settings layout. Captured reference in
`build/ev-settings-reference.png`; stock geometry is 250/600/426 dp below a
60 dp centered title. Tweaks now uses those three independently scrolling panes:
all feature names, selected checkbox/options, and full explanation. There is no
search bar or separate gear/help page. Options save immediately. Installed stock
row/checkbox graphics are resolved at runtime, with a local fallback for the
emulator. The status square order is Wi-Fi / ADB above Tweaks / Precondition.
Preserve all current vehicle selections when deploying this UI change.

Validation for this checkpoint: API 19 release build and v1 signatures verified;
80 Java tests and five installer hook checks passed. Standalone lint reports
zero errors (35 launcher warnings, 11 Tweaks warnings). Emulator interaction
checks verify the pinned status square, protected divider during drag/reorder,
three panes without search, immediate heater option saves, independent scrolling,
and remembered selection visible after reopening. Car screenshots confirm the
installed EV Settings graphics and pane geometry. Device config compared equal
before/after update: all six selected; 50°F threshold, driver level 1, steering
wheel on, passenger off. Release APKs and refreshed USB bundles are in `dist/`.
Reference and resulting screens: `build/ev-settings-reference.png` and
`build/ev-preview/car-tweaks.png`. No vehicle EV Settings values were changed.

## Fast settings read and compact DST checkpoint — 2026-11-04

The app used the diagnostic `status` command on opening and after every save.
That audits system files and hashes the 154 MiB paired panel APK twice. Measured
5.96 seconds through ADB on the car. Added `settings`, which loads configuration
and small listener/restart status files while preserving pending transaction
recovery. The app uses it for initial load and readback; file/vehicle compatibility
checks are still enforced before writes. Diagnostic `status` remains available.
Measured installed `settings` reads: 0.95, 0.93 and 0.98 seconds. The header now
distinguishes Saving changes from Reading settings. DST uses tighter note padding
and 54 dp schedule rows; all three schedules are visible without scrolling in
the real car's UI dump and screenshot. All stored tweak options compared equal.
API 19 release, pinned v1 signatures, 85 host checks and zero-error lint passed.
Installed final bundle and restarted head unit; left the Tweaks screen open.
Evidence: `build/ev-settings-timings.txt`, `build/ev-preview/car-dst.xml`, and
`build/ev-preview/car-tweaks.png`.

## Nonblocking heater options checkpoint — 2026-11-04

User reported five-second saves for every degree adjustment. Initial-load
optimization had left option writes on the full patch transaction path. Added
Transaction.updateHeaterOptions and the root `options` command. This closed path
accepts only the seven heater options, validates all pairs before an atomic
configuration replacement, and returns settings in the same root invocation.
It never calls the patch callback, restores DST, or sends vehicle commands.
Pending patch transactions must recover before this path can run. Master
switches retain their normal patch/vehicle validation.

The UI updates heater options immediately, coalesces edits after a 300 ms pause,
and keeps option controls enabled during writes. Generation checks discard stale
responses. onPause queues the latest snapshot so leaving before the debounce
expires does not lose edits; failures are shown and reconciled from stored state.
Debug preview exercises the same queue with simulated persistence.

84 Java tests plus five installer checks pass. New tests cover preserving
switches, excluding every master switch/DST region, rejecting mixed invalid
options without partial commits, and requiring pending recovery. Standalone
lint reports zero errors. Emulator interaction checks confirm rapid taps,
enabled controls, flushing on exit, and persistence across process restart.
Release artifacts and USB bundles rebuilt with the pinned API 19 signature.
Installed on the car and restarted; real-car timing/interaction checks follow.

Device verification pending: install completed at 16:47:57 local, then root
restart was requested. The car has not returned at 192.168.0.23:5555; ADB reports
No route to host and the ARP entry is FAILED. Emulator remains reachable.
Do not claim an on-car timing result yet. When connectivity returns, run
`python3 build/ev-preview/check_car_heater_options.py`: it times three same-value
option writes, checks three real UI increments, and restores only the threshold
changed by that test. The pre-update car config is
`build/heater-options-before.txt`. Release is already installed; no further
reinstall is necessary solely for verification.

## General settings performance checkpoint — 2026-11-04

User requested generalizing the heater-only optimization to all controls. The
settings APK now uses a thread-safe SettingsEdits queue for every switch/option.
There is one active batch and at most one latest value per pending key; newer
edits overlay responses, toggling back before dispatch cancels the work, and
the worker drains pending edits independently of Activity callbacks so leaving
the screen cannot drop them. All controls stay enabled during writes.

Root `update` validates and commits changed keys and returns settings in one
process. No-op selections skip writes. Config-only changes (including heater
enablement and DST region) use atomic config replacement. Vehicle validation
is limited to relevant newly enabled features/enabled DST region changes.
Static patches are scoped to changed recipes; shared panel transforms retain
the other panel setting. Scoped transactions persist a delta marker through
process death; rollback uses the same affected scope and restores live DST
when necessary. Legacy pending intents and explicit Restore perform a full
audit. DST config observation ignores unrelated settings edits.

105 checks pass (100 Java plus five installer checks); standalone lint has zero
errors. All 16 static combinations on real captured files pass and restore exact
stock bytes; modified ODEX files independently disassemble. Emulator integration
checks exercise all six switches and DST region, rapid heater edits, save on
leaving, and process restart persistence. Built/signed release and refreshed USB
bundles; installed on car at 16:59:05 local and requested restart.

Next: once ADB reconnects, run
`python3 build/ev-preview/check_car_general_settings.py` to time real update
commands and check rapid UI edits (restoring the original threshold afterward).
Compare config against `build/general-settings-car-before.txt`, and record
results here. Keep all existing user changes on feature/bundled-tweaks.

Post-restart verification remains blocked by connectivity: car ADB still reports
No route to host; only the emulator is connected. Asked the user to reconnect
Wi-Fi/ADB so final car timing checks can run. Do not report new on-car timing
measurements yet. Installation is complete and all release checksums match.

## Climate tweaks, star trigger and state text — 2026-11-04 (evening)

Added and verified on the car with the user:

- **longstar**: the hold-to-open service (formerly `knobd`/`KnobDaemon`) now
  listens for the steering-wheel ★ (scancode 43, `longstar.conf`) and starts
  Tilepad in-process (`startActivityAsUser`, ~600 ms faster than `am`). The stock
  ★ long-press broadcast (`SWRC_CUSTOM_SHORTCUT`, which opened Button Settings) is
  aborted by Tilepad's manifest `StarHoldReceiver` and forwarded only to NEMESIS
  Manager. A daemon-registered receiver can't do this: AMS skips ordered
  broadcasts to it ("process crashing").
- **Maintain Climate Sync** (`sync`) and **Automatic Driver Only** (`driveronly`),
  `ClimatePolicy`/`ClimateController`; mappings in TWEAKS.md "Climate tweak
  details". Temperatures arrive only via `onTemperatureInformation`; passenger
  presses are DATC-internal, so the driver is stepped to match (one step in
  flight, per-direction pending counts, 700 ms quiet before resync, driver press
  wins, manual Sync-off pauses). Automatic Driver Only resets per ignition cycle
  (`getIGNStatus`), not per boot. Seat occupancy getters read 0 on this car.
- Right-hand pop-over and nav handle share one left-pane entry ("Right-Hand
  Pop-Over Settings"); disabling the pop-over forces `nav=true` (UI and backend).
- Every no-settings tweak shows a state sentence under its checkbox; file tweaks
  report `pending.<id>` from the boot-start `applied.<id>` record in
  `runtime.properties`.

116 Java tests + 5 installer checks pass; release build signed. The last car
install (before the copy/merge/pause changes) verified Maintain Climate Sync,
the ★ hold and the ignition-cycle reset; the state-text/merge/pause build is
built but not yet installed. Car reboot: `nemesis root 1000 -c "setprop
sys.powerctl reboot"` (reboot.tool is gone).
