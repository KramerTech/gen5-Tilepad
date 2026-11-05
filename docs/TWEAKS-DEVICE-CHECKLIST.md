# First-draft verification still required

No car was modified during development. Keep the generated uninstall USB bundle
available. This checklist is consolidated here so on-device questions/testing can
be handled once the draft is ready.

November 4 update: the user authorized installation. The full package is now on
the car, both apps were loaded after a head-unit restart, and root listener
bootstrap passed on the emulator and car. All new switches remain off. The
pre-existing navigation-handle patch was preserved and currently prevents the
two new panel switches from taking ownership. ADB went offline during the last
Tweaks-screen check. Physical effects and the unchecked interaction tests below
remain unverified; see the mission document for the backup location and fixes.

Later November 4 update: the user requested legacy-nav cleanup. The known patch
is now managed by Tweaks (`nav=true`) with verified stock restoration data, and
both panel controls report available. Pop-over suppression remains off. Other
settings enabled by the user since installation were preserved. Ownership
transfer did not rewrite the active panel code or require a reboot.

## Emulator, when ADB access is available

- [ ] Install both debug APKs on `emulator-5580`, the captured-theme API 19 AVD.
- [ ] Preview Tweaks with `--ez design_preview true`: six unchecked rows, readable
  text, correct checkbox hit targets, scroll reachability and clear preview banner.
- [ ] Read every help page, including the longer pop-over explanation; ensure Back
  returns to the previous scroll position.
- [ ] Only enabled DST/heaters show gears. Check all heater options, independent
  seat levels, threshold boundaries, °F/°C conversion, Save and Cancel.
- [ ] Open Tilepad's Tweaks tile after Precondition. Verify saved/custom arrangements
  retain shortcuts and the core tile cannot be removed or renamed.
- [ ] Execute the root listener bootstrap in the emulator with OEM mocks. The
  production vehicle gate should refuse writes on an emulator; fake service
  acceptance alone must not be called a hardware test. Event registration and
  root-context initialization still need to be exercised independently.
- [ ] Check logcat for crashes, especially after leaving/re-entering the APK.

## Parked head unit: install and lifecycle

- [ ] Disable equivalent Cabinwright/other tweaks before handing ownership to
  Tilepad. Confirm captured supported files, rather than overwriting another mod.
- [x] Install/update the whole bundle through NEMESIS, reboot, and confirm both Tilepad and
  Tweaks are present. Existing ADB/Wi-Fi state and shortcuts survive the update.
- [ ] Steering-wheel ★ hold opens/closes Tilepad (short ★ keeps its stock action); the only NEMESIS resident service is
  `tilepad/longstar`. Tweaks reports its listeners running.
- [ ] All switches are initially off; unsupported files have a readable reason.
- [ ] Measure longstar memory and startup behavior with all off, then DST/heaters on.

## Parked head unit: feature checks

- [ ] **DST:** select the correct region and base timezone. On the November 2026
  US/Canada schedule standard time should now be active. Verify both displayed
  time and the factory checkbox, timezone/date correction handling, display wake,
  and restoring the previous flag/checkbox when disabled. Seasonal instants are
  host-tested; don't change a live car's clock merely to simulate a boundary.
- [ ] **Startup brief:** enable and reboot. Verify no brief flashes, normal home,
  audio, navigation and agreement-dependent features initialize. Check ACC off/on
  and a profile change without a full head-unit reboot. Disable, reboot and verify
  the original brief returns.
- [ ] **Heaters:** set threshold above the actual outdoor temperature for a cold
  test, choose each supported actuator and verify physical heat/indicator levels
  1/2/3. Passenger selection must be independent. Reboot for each automation trial.
  Check the warm case (threshold below outdoor temperature), missing-sensor case
  if practical, and that manual changes survive a longstar service restart. Turning the car
  off/on may not reboot its head unit. Accepted requests are not physical proof.
- [ ] **Track swap:** determine the active `keyboard.layout`/key-layout path, then
  verify up=next/down=previous with Bluetooth/USB/projection media and radio after
  reboot. Confirm ★, TUNE and unrelated buttons still work. Disable and reboot to
  check original mappings.
- [ ] **Navigation handle:** enable/reboot; check its absence across stock apps and
  Tilepad. Test the other available navigation controls. Disable/reboot to restore.
- [ ] **Right pop-over:** enable/reboot. Check automatic track changes, physical
  track buttons, radio/media-source changes and projection while another app is
  visible. Confirm no transient panel or blank/untouchable reserved strip appears.
  Check HUD information and audio still work. Verify navigation, camera and other
  important displays remain usable; notices that share the suppressed OSD are
  intentionally hidden. This is the highest-uncertainty new patch.
- [ ] **Panel combinations:** handle only, pop-over only, both on, then disable one
  at a time, rebooting after each change. The other setting must stay effective.

- [ ] **Maintain Climate Sync:** synced, press passenger up twice: driver follows to the same
  setting and Sync comes back on. Same for down. From the screen, turn Sync off: it
  comes back on. Driver Only stays on when selected. No loops with the climate off.
- [ ] **Automatic Driver Only:** each car start (including a short off/on that keeps the
  head unit running) sets Driver Only once the climate is on. Buckling the passenger
  belt switches to Sync within about a second; unbuckling doesn't switch back until
  the next car start. Restarting longstar mid-drive repeats neither step.
- [ ] **Steering-wheel ★:** a ~1 s hold opens Tilepad, a second hold closes it, and
  a short press still runs its assigned Button settings action (currently
  Connectivity). A hold must not open Button Settings; NEMESIS Manager's own
  press trigger must still work.

## Restore and update

- [ ] Update while enabled: switches, original backups and resulting behavior
  survive; no second boot heater attempt occurs just from restarting the longstar service.
- [ ] Disable all static switches and reboot: original file hashes and behavior
  return. Existing factory media-notification preferences are retained.
- [ ] Uninstall through the USB bundle: owned patches/DST restore before APKs are
  removed, and reboot returns the stock UI. Existing Wi-Fi-client/ADB uninstall
  behavior follows Tilepad's documented policy.
- [ ] If normal UI access fails, use the USB bundle's `tilepad/restore-tweaks.sh`
  through NEMESIS root execute. Keep backups if any verification refuses a restore.

## Evidence to collect if a check fails

Tilepad install/update log from USB; `tilepad/longstar` log from NEMESIS Manager;
the root command `sh /data/nemesis/apps/tilepad/bin/tweaks.sh status`; and a photo
or emulator screenshot of the specific UI problem. The stock-file backups and
journals are in `/data/nemesis/backup/tilepad-tweaks`; retain them rather than
deleting them to force a checkbox to enable.
