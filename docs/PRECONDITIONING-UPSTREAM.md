# Preconditioning upstream integration

Compared Tilepad's original backend with
[paytondevs/gen5w-preconditioning](https://github.com/paytondevs/gen5w-preconditioning),
pinned at [`0e366bdaee27`](https://github.com/paytondevs/gen5w-preconditioning/commit/0e366bdaee2768131e00eba1a5333e961ffd879b).
After replacing only the Java package name, Tilepad's original
`PreconditioningProfile`, `OemVehicleClient`, and `PreconditioningController`
exactly match upstream's initial commit
[`c97fafad688e`](https://github.com/paytondevs/gen5w-preconditioning/commit/c97fafad688e).
Tilepad has independent launcher, trigger, popup and service adaptations, so this
integration ports the useful backend changes instead of merging the application wholesale.

## Imported changes

- Exact firmware profiles for ten combinations across six head-unit families.
  Android API 19, device code, `ro.build.product` (not `Build.PRODUCT` or the
  displayed `ro.build.product2`), and the matching pair of framework hashes must
  all agree. Hashes from different profiles cannot be mixed. HEV/BCM Binder
  descriptors and reply sizes remain checked. The existing Start metadata,
  single Start pulse, maintenance and cancellation sequences are unchanged.
- Per-profile unconfirmed-activation deadlines: five minutes on US EV6 units;
  two minutes on IONIQ 5, IONIQ 6 and Korean EV6. A fresh Active indication at
  the deadline wins. Already-active sessions have no activation timeout. Missing
  or reserved feedback cannot keep an unconfirmed request alive indefinitely.
  Timeout sends cancellation once and retains ownership until cancellation
  succeeds and a fresh Off reading arrives. Failed cancellation remains retryable.
- A one-shot minimum battery-temperature precheck, using the existing cluster
  interface. It verifies the service descriptor, registers a temporary callback,
  requests the array and removes the callback even after an uncertain failure.
  It validates the eight-element reply and minimum-temperature field. There is
  at most one in-flight helper, with an overall 1.8-second deadline, so a hung
  Binder call cannot accumulate threads or indefinitely block the controller.
  Late or unavailable temperature falls back to the existing manual request.
- A ten-second warm advisory, without recording ownership or sending Start.
  Thresholds follow upstream: 20°C for US EV6 and IONIQ 6; 21°C for IONIQ 5 and
  Korean EV6. They are advisories, not guarantees about BMS calibration. The
  popup offers an explicit **Start anyway**; an ordinary duplicate Start or
  another tile tap cannot accept it. READY and Off are rechecked after telemetry
  and before an override. The original one-tap Start and active-session Stop
  actions otherwise remain in place.
- Service reattachment calls the existing bind routine from both `onBind` and
  `onRebind`, and returns true from `onUnbind`. Reopening during a live session
  resumes the existing status binding; an idle unbound service still stops.

Key upstream changes reviewed include `d8bb05a`, `afe9704`, `fb605cf`, `47fc940`,
`6cde3f5`, `3d6b2bb`, `28ec556`, `86ebbb1`, `a7a927c`, `9f7aca6`, `a8303b0`,
`5010bec`, and `edb8f13`. Latest profile constants and matching logic come from
pinned HEAD. The minimal bounded temperature reader comes from `3d6b2bb`, before
scheduler-specific outside-temperature telemetry was added.

## Supported preconditioning identities

| Head unit | Software suffixes |
| --- | --- |
| IONIQ 5 NEEV23 US | 260206, 260507, 260903 |
| IONIQ 5 NEEV US | 240822, 260507 |
| IONIQ 5 NEEV KR | 260902 |
| EV6 CVEV US | 251120, 260507 |
| EV6 CVEV KR | 260902 |
| IONIQ 6 CEEV US | 260507 |

These are exact software/hash combinations, not support for every model year or
firmware with the same suffix. Evidence and field-testing limits are in the
[pinned compatibility notes](https://github.com/paytondevs/gen5w-preconditioning/blob/0e366bdaee2768131e00eba1a5333e961ffd879b/COMPATIBILITY.md)
and [temperature/timeout notes](https://github.com/paytondevs/gen5w-preconditioning/blob/0e366bdaee2768131e00eba1a5333e961ffd879b/TEMPERATURE-AND-TIMEOUT.md).
The captured `/system/build.prop` contains generic ECOHMC defaults; the live
runtime properties are authoritative. A read-only check on the existing car
confirmed `wp_daudioplus_neev23_us` and
`NEEV23.USA.0000.V015.012.260507`, matching the original framework hash pair.
Compatibility of other Tilepad tweaks is independent; their gates were not broadened.

## Service and UI boundaries

All control ownership stays in Tilepad's existing `PreconditioningService`, its
serialized worker, private ownership journal and ongoing notification. The
cluster helper is a bounded thread, not another Android service or permanent
subscription. No service, receiver, boot action, alarm or permission was added.
The root star-hold service still only opens Tilepad; moving preconditioning into
that process would couple vehicle ownership to the launcher trigger unnecessarily.

Not imported: scheduled/automatic Start, the redesigned upstream app and tabs,
installer changes, TUNE remapping, graphs, temperature history, continuous
telemetry polling, or the scheduler's unverified outside-temperature scaling.
The only popup additions are warm consent and confirmed activation-timeout text.

## Verification

- `sh tools/tweaks/test-host.sh`: pure Java regression/profile tests, installer
  checks, and the two Android/Binder fixture suites below. Compatibility tests
  cover every profile and reject wrong devices, software, APIs and mixed hashes.
- `python3 tools/preconditioning/test_temperature_binder.py`: ten scenarios for
  valid/malformed responses, request/registration/removal failures, dead/wrong
  services, a hung transaction, late results and overlapping reads.
- `python3 tools/preconditioning/test_service_rebind.py`: six scenarios running
  the actual service with Android fixtures: Start, unbind, rebind, idle shutdown,
  explicit warm consent and eligibility loss during temperature collection.
- `sh tools/build_apk.sh --sdk-only`: release compilation against Android API 19,
  DEX 035 generation and verification of the pinned v1 signing certificate.
- Emulator popup flow and standalone Android lint are checked separately.

The debug-only emulator adapter accepts `debug.tilepad.battery_min_c` for testing
warm consent. It is ignored outside the emulator and excluded from release code.
No Start/Stop commands were sent to the real vehicle as part of this integration.

Integration results: 172 Java tests, five installer checks, ten Binder fixture
scenarios and six service fixture scenarios pass (193 checks total). Standalone
lint reports zero errors. Release APKs compile for API 19 and verify against the
pinned v1 certificate. On the emulator, the existing tile opened the warm popup,
explicit Start anyway produced Waiting for BMS, and Cancel confirmed Off and
cleared the ownership journal. Screenshots are in `build/precon-preview/`.
The production manifest and root star-hold service/configuration are unchanged.
