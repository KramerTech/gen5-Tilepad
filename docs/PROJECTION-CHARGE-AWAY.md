# Auto-Open Phone Projection and Charge-Away

Implementation mission (2026-11-04): add two opt-in tweaks to the bundled Tweaks
APK and existing in-process runtime. Preserve the preconditioning upstream work
already in the working tree and all existing tweak behavior.

- Auto-Open Phone Projection: when Android Auto or CarPlay becomes available,
  launch the stock Phone Projection screen. Detect the underlying OEM connection
  state, independently of the right pop-over, and open once per availability
  transition rather than repeatedly stealing focus.
- Charge-Away: let the user save the current valid vehicle location as Home and
  show its latitude/longitude. While enabled, inspect location every ten seconds.
  Beyond 1.5 statute miles, turn Scheduled Charging off; within 1 mile, turn it
  on. Between the thresholds retain the last state. Do not change the separate
  Charge ONLY during Off-peak choice, schedule times, or departure preferences.
  Persist home and the region state, handle stale/missing fixes without guessing,
  and document the behavior of disabling the tweak and manually changing the EV setting.

Primary evidence: full offline head-unit suite at
`../gen5-offline-emulator/investigation/offline-headunit/20261031`, including
`interactive/ev-schedule`, callback recordings, lab mappings and captured OEM
APKs/ODEX. Reverse engineer only the exact interfaces actually present; no
blind transaction probing or schedule changes on the car during investigation.

## Implemented behavior

Both switches default off and appear in the stock-style three-pane Tweaks UI.
The Tilepad summary automatically counts all ten selections. Existing optimistic
settings saves are retained; setting, updating or clearing Home is a separate explicit action (clearing asks first), shows
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
session when enabled. Duplicate notifications, leaving projection manually, and
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

## Evidence and compatibility

The offline head-unit index, service catalog, Parcel mappings and resolved OEM
code were consulted. The `interactive/ev-schedule` recording explicitly canceled
its attempted save, so it was not treated as setter or acknowledgement proof.
The actual stock EV3 save method and IHEVService proxy establish these mappings:

| Interface | Verified mapping |
| --- | --- |
| `gpm`, `com.hkmc.gpm.IGpmInterfaces` | transaction 1; status >=4 means connected |
| `dipo`, `android.dipo.IDiPOManager` | transaction 2; status 4 means activated |
| `com.hkmc.micom.hev`, `com.hkmc.hev.IHEVService` | 1/2 add/remove listener, 14 request feedback, 30 set nine fields |
| `com.hkmc.hev.IHEVListener` | callback 4 provides 12 economic-charge fields |
| Stock preference | `economic_only_economic_mode`: 0 prefer, 1 only off-peak |

Exact vehicle/platform and captured framework/EV APK hash gates refuse unknown
firmware. This intentionally does not broaden the separate multi-model
preconditioning support. Additional firmware needs verified mappings and hashes.
No OEM binary or private GPS dump is shipped.

On 2026-11-04 the read-only `tools/tweaks/probe/TravelProbe.java` ran on the
connected NEEV23 US 260507 head unit. Both projection interfaces and the root foreground-task read passed, fresh
charging callback returned 12 fields, mode 0 and configured times, the independent
only-off-peak preference was 1, and GPS was fresh with 3 m accuracy. No charging
write, Home save, or projection launch was sent to the car by this probe.

## Validation and remaining physical checks

Pure policy tests cover GPS parsing/freshness, accuracy-aware boundaries,
hysteresis, field preservation, home validation and projection connection edges.
`tools/tweaks/test_travel_runtime.py` executes actual controllers and charging
adapter against deterministic Android/Binder fixtures using the recorded getter
shape: duplicate/restart/camera/failure projection behavior, Home changes and
disable during an in-flight read, charging confirmation/retry bounds, malformed
feedback, callback timeouts and listener cleanup. The emulator validates the
three-pane UI and saved Home presentation. Host/emulator checks do not establish
physical actuation.

The final phone-connection, camera, travel-boundary and real charging-state checks
are listed in [the device checklist](TWEAKS-DEVICE-CHECKLIST.md). No live scheduled
charging writes were used during development.

Validation completed for this draft: 185 Java tests, 5 installer checks,
21 travel controller/Parcel scenarios, 10 preconditioning Binder scenarios and
6 service/precheck scenarios passed (227 checks total). API 19 builds and both
lint runs passed with zero errors; existing platform/API warnings remain, plus
an intentional OEM private-preference-path warning. Release APKs verify against
the pinned Android 4.4 v1 signing certificate. All USB bundles and checksums were
rebuilt. Emulator previews verified both new switches, Home coordinate display,
and persistence after reopening; prior preview selections were retained.

## Car deployment, 2026-11-05

Connected to the verified head unit at `192.168.0.24:5555` and updated Tilepad
through `nemesis install --update`. Both installed system APKs were pulled back
and their SHA-256 hashes matched the verified releases. The entire tweak config
was byte-for-byte unchanged. The previous package, system APKs and tweak state
were backed up at `/data/nemesis/backup/tilepad-before-travel-20261105`.
Installation evidence is under ignored `build/travel-car-install`.

A head-unit reboot was issued to load the replacement system APKs and Dalvik code.
Post-reboot connection/service/UI verification is pending reconnection. Both new
features retain their default off state; no Home was saved or charging setting
changed during installation.

## November 5: confirmed auto-open failure and correction

The wired Pixel test reached native AA status 5, then 6, played music and showed
the stock connection header while Tilepad stayed foreground. The persisted seen
mask remained zero. Android's log showed both `gpm.set` and
`UPDATE_ANDROIDAUTO` being skipped to production and diagnostic root receivers:
`BroadcastQueue: ... process crashing`. These processes were healthy; their
ReceiverList has no attached Android app (`app=null`), so the OEM ordered delivery
path refuses them. Passing a null caller thread during registration does not
solve ordered delivery. The OEM `GPM_CUSTOM_SHORTCUT` action was sent manually
and immediately brought `com.google.android.projection.sink.ui.MainActivity`
forward, verifying the launch path independently.

The existing runtime now registers a native `com.hkmc.gpm.IGpmListener` with
`IGpmInterfaces` transaction 9 (remove: 10). Callback 1 is
`onConnectStatus(int)` and 2 is `onServiceKill(boolean)`. These mappings were
extracted from the captured framework2 ODEX with explicit API 19 decoding, then
registration from root was confirmed on-device by an immediate status-6 callback.
Callbacks post back to the runtime handler before querying any native service,
avoiding re-entry while an OEM callback thread may hold its service lock.

A two-second in-process status check while enabled covers CarPlay, late state,
service restarts and callback loss, without forked shell polling. Unknown reads
retain the previous side's state. Native listener registration is retried without
blocking a working status check. Compatibility gates still run before attaching,
and unknown firmware is refused. The obsolete root projection receiver was
removed; no new APK receiver, Android/NEMESIS service or resident daemon was added.
Only changed connection states, callback registration/events, launch requests and
launch-command responses are logged; there is no per-poll log spam. Status says
that opening was requested, rather than treating a successful broadcast command
as proof that the projected activity became visible.

Controller/Parcel fixtures additionally cover native AA without any broadcast,
AA state 5-to-6 deduplication, CarPlay without broadcasts, late-state recovery
when callback registration fails, removal/no polling when off, and bounded
launch retries despite periodic checking. Physical reconnect verification follows
installation of this correction.

Installed the correction via NEMESIS on November 5, 2026; both system APKs were
pulled back and matched the signed releases. Settings were byte-for-byte preserved.
A backup is retained at `/data/nemesis/backup/tilepad-before-projection-fix-20261105`.
After restarting, the user confirmed automatic opening worked. Production logs
show native connection states 3, 0, 3, 4, 5; the status-5 edge issued exactly one
`GPM_CUSTOM_SHORTCUT` request from the stock launcher. State 6 then arrived and
the saved seen mask was 2. ActivityManager independently confirmed Android Auto's
MainActivity resumed/focused, and `tilepad/longstar` was running (PID 241).
The standalone diagnostic processes ended with the restart and their device DEX,
cache and logs were removed after saving evidence under private `/tmp` directories.

Final validation: 194 Java tests, 5 installer checks, 28 travel controller/Parcel
scenarios, 10 preconditioning Binder scenarios and 6 service scenarios passed
(243 total). API 19 release builds, signing verification, both lint runs (zero
errors), rebuilt USB bundles/checksums and `git diff --check` passed. CarPlay
without broadcasts is fixture-tested; no iPhone connection was physically tested.
