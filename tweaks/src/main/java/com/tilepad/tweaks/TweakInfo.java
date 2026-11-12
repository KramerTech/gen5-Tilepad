package com.tilepad.tweaks;

final class TweakInfo {
    final String id, title, summary, help;
    final boolean options, restart;
    // The line under the checkbox: on/off, and for file tweaks the wait for a head-unit restart.
    // Tweaks with settings keep their own runtime status instead. A tweak's runtime status, if
    // any, is shown under this line.
    private String on, off, onPending, offPending;
    /** Left-pane label when it differs from the checkbox title. */
    String rail;
    /** A second checkbox shown with this one (the nav handle under the pop-over); it has no left-pane entry. */
    TweakInfo companion;
    TweakInfo(String id, String title, String summary, boolean options, boolean restart, String help) {
        this.id = id; this.title = title; this.summary = summary; this.options = options; this.restart = restart; this.help = help;
    }
    private TweakInfo states(String on, String off, String onPending, String offPending) {
        this.on = on; this.off = off; this.onPending = onPending; this.offPending = offPending; return this;
    }
    private TweakInfo with(String label, TweakInfo second) { rail = label; companion = second; return this; }
    String railTitle() { return rail != null ? rail : title; }
    /** The current state in a sentence or two, or null for tweaks with settings, which show their runtime status. */
    String state(boolean enabled, boolean pending) {
        if (off == null) return null;
        if (enabled) return pending && onPending != null ? onPending : on;
        return pending && offPending != null ? offPending : off;
    }

    private static final TweakInfo NAV = new TweakInfo("nav", "Hide Right-Hand Nav Handle", "Hides the nav handle at the right edge of most screens.", false, true,
            "The nav handle is the semicircle on the right edge of the screen that shows in most apps and opens the system nav as a pop-over. Hiding it makes the handle's stock controller take its hidden path; it does not repeatedly dismiss a handle that has already appeared. Hiding the handle removes that touch shortcut; use the physical controls or other navigation controls when needed.\n\n"
            + "It is always hidden while the right-hand pop-over is disabled. With the pop-over allowed, it can be hidden on its own. Both use the verified local RightScreenHome patch and share one stock backup, so changing one does not undo the other. Both enabling and disabling need a head-unit restart. Unknown firmware or conflicting panel modifications are reported before writing.").states(
            "The nav handle, which is a semicircle on the right side of the screen that displays in most apps and opens the system nav as a pop-over, is globally hidden.",
            "The nav handle, the semicircle on the right side of the screen that opens the system nav as a pop-over, shows in most apps.",
            "The nav handle will be hidden after the next head-unit restart. Until then, it still shows.",
            "The nav handle comes back after the next head-unit restart. Until then, it stays hidden.");

    /** Under the Automatic Heaters passenger-seat checkbox, whether it's on or off. */
    static final String PASSENGER_HEAT = "Heats only when someone is in the passenger seat: the seat heats once the front passenger's seat belt is buckled, even if that's later in the drive. Safe to leave on if you usually drive alone.";

    /** Charge-Away's line while it's on with no Home saved yet. */
    static final String CHARGE_AWAY_NO_HOME = "Set your current location as Home to begin. Until then, Scheduled Charging is left as it is.";

    /** Every tweak, for lookups by id. */
    static final TweakInfo[] ALL = {
        new TweakInfo("dst", "Automatic Daylight Saving Time", "Keeps the head unit’s daylight-saving switch in step with your region.", true, false,
            "Automatically turns Hyundai’s own daylight-saving setting on and off. The base timezone you selected in Hyundai Date/Time settings stays in place; this adds the seasonal hour when appropriate. It also keeps the factory daylight-saving checkbox in step.\n\n"
            + "Choose US/Canada, Europe, or No daylight saving in the middle pane. US/Canada uses the second Sunday in March and first Sunday in November. Europe uses the last Sunday in March and October at 01:00 UTC. A fixed timezone offset does not identify where you live: Arizona, Hawaii and other exceptions need No daylight saving. These schedules are explicit rules; they do not update themselves if the law changes.\n\n"
            + "Works offline with an accurate clock. It checks at head-unit boot, clock/timezone/date changes, waking the display, and the next scheduled transition. There is no minute-by-minute polling. While enabled it overrides manual daylight-saving changes. Disabling restores the previous setting where Tilepad still owns it. A failed clock/service check is shown here instead of guessing."),
        new TweakInfo("consent", "Skip Startup Safety Brief", "Goes straight past the confirmation screen at head-unit startup.", false, true,
            "Prevents the normal startup confirmation screen from being constructed. It uses the factory agreement path so the rest of the system receives its normal agreed flags and broadcast. It does not wait for the screen to appear and press Confirm.\n\n"
            + "Only startup changes. The same screen is the driver picker, so switching drivers, entering a driver profile's password and the notice after a software update still show it. At startup the current driver stays selected, and a profile with a password isn't asked for it then.\n\n"
            + "Changes the captured, supported SystemUI optimized code. The original file is verified and backed up before any change. Enabling and disabling take effect after a full head-unit restart. A car off/on cycle may leave the head unit running and may not apply the change.\n\n"
            + "This skips the reminder only; it does not alter driving restrictions, driver-assistance systems, or vehicle safety features. If another mod already changes SystemUI, switch that mod off first. The checkbox reports an unsupported file instead of overwriting it.").states(
            "The safety brief is skipped, so the head unit goes straight to the home screen when it starts.",
            "The safety brief shows every time the head unit starts, and you need to press 'Confirm' to dismiss it.",
            "This takes effect after the next head-unit restart. Until then, the safety brief still shows.",
            "This turns off after the next head-unit restart. Until then, the safety brief is still skipped."),
        new TweakInfo("heaters", "Automatic Heaters", "At a cold head-unit boot, switches on only the heaters you select.", true, false,
            "Once per head-unit boot, reads the outdoor temperature and, only when it is strictly below your threshold, sends one request to each selected heater. Choose the driver seat, steering wheel and passenger seat independently. Each seat has its own level: 1 Low, 2 Medium, 3 High. Only enable equipment your vehicle has.\n\n"
            + "The passenger seat only heats when someone is sitting there. If it's cold enough at boot, Tilepad waits for the front passenger's seat belt to be buckled and heats the seat then, whether that's right away or later in the drive. If nobody buckles up before the car is turned off, the seat stays off. So it's safe to leave the passenger seat selected even if you usually drive alone. (The car doesn't report seat weight to the head unit, so the seat belt is the only sign of a passenger.)\n\n"
            + "The controls in the middle pane set the threshold in °F or °C, select heaters and set seat intensity. Unit selection changes how the threshold is displayed, not how the vehicle sensor is interpreted. No valid temperature means no heat command: Tilepad waits for up to two minutes, rejects unavailable/uninitialized sensor values, and then skips that boot. At the exact threshold nothing is heated.\n\n"
            + "Enabling or changing settings applies at the next head-unit boot, not immediately. Restarting Tilepad’s service does not repeat the requests. Turning the car off/on may not reboot the head unit, so the heaters aren't necessarily started on every drive. Tilepad never keeps forcing a level after you change the controls. Disabling stops future automation and leaves current heater settings for you to adjust. An accepted request still needs the normal vehicle conditions for heating to work."),
        new TweakInfo("tracks", "Swap Steering-Wheel Track Buttons", "Up selects the next track; down selects the previous track.", false, true,
            "Reverses the steering-wheel track-up and track-down mappings so up means next and down means previous. Radio channel stepping follows the same reversed mapping where the stock system uses these keys. Knob movement and other buttons keep their own mappings.\n\n"
            + "Changes only the two track-key entries in the key layout the car loads, which every model's layout shares. The swap uses no input grab, injected keys, or background listener. Android reads key layouts when devices initialize, so restart the head unit after enabling or disabling.\n\n"
            + "The original layouts are retained and restored exactly when disabled. If the layout differs or another remapping mod owns it, Tilepad refuses to overwrite it. The final direction must be verified on the car with both media and radio.").states(
            "The steering-wheel track buttons are swapped: up skips to the next track, and down goes back.",
            "The steering-wheel track buttons work Hyundai's default way: up goes back a track, and down skips ahead.",
            "The buttons will be swapped after the next head-unit restart. Until then, they still work the default way.",
            "The buttons go back to Hyundai's default after the next head-unit restart. Until then, they stay swapped."),
        new TweakInfo("popover", "Disable Right-Hand Pop-Over", "Stops transient media and information panels covering your screen.", false, true,
            "Blocks the shared right-side OSD panel before it can display. This includes the temporary media/information pop-over and forced requests using that same panel. It blocks the request path and shared renderer, rather than closing the panel after it covers your screen. Disabling the pop-over also hides the nav handle.\n\n"
            + "The factory Display settings include Media change notifications, but that option still allows notices triggered by physical buttons. This tweak also blocks those forced requests. It leaves your factory media-notification preference in place for when you turn the tweak off.\n\n"
            + "This is a broad right-panel switch: other notices using the same OSD panel will also be hidden, not only track changes. It doesn't change the HUD, media playback, full-screen navigation or the reversing-camera app.\n\n"
            + "Works only with the exact RightScreenHome files of a supported firmware. Restart the head unit after enabling or disabling. The original code is backed up, and disabling restores it while preserving the handle setting. If you already have another panel mod installed, turn it off before enabling this one.\n\n"
            + NAV.help).states(
            "Media and information pop-overs are blocked, so they no longer cover the right side of the screen.",
            "Media and information pop-overs appear on the right side of the screen, for example when the track changes.",
            "Pop-overs will be blocked after the next head-unit restart. Until then, they still appear.",
            "Pop-overs come back after the next head-unit restart. Until then, they stay blocked.")
            .with("Right-Hand Pop-Over Settings", NAV),
        NAV,
        new TweakInfo("sync", "Maintain Climate Sync", "Locks the passenger temperature to yours, so the two sides never differ.", false, false,
            "Keeps the driver and passenger temperatures together. When the passenger temperature is changed, with the passenger buttons or the climate screen, the driver temperature is moved to the same setting and Sync is turned straight back on. Either side's buttons then adjust the whole cabin. If the driver temperature is changed while the driver side is still catching up, the driver's new setting wins and the passenger side follows it.\n\n"
            + "Turning Sync off yourself in the climate settings pauses the lock, so you can set the two sides apart when you want to. The lock resumes when Sync is pressed again or the head unit restarts.\n\n"
            + "The passenger buttons are handled by the climate controller itself, not the head unit, so Tilepad follows each press rather than replacing it: you may see the two temperatures differ for a moment and Sync blink off and on. The driver temperature is moved one step at a time, the same as pressing its own buttons.\n\n"
            + "Driver Only is left alone, so this works alongside Automatic Driver Only. Takes effect immediately; no restart is needed. Turning the tweak off leaves the climate as it is.").states(
            "The passenger and driver temperatures are locked together, and 'Sync' stays on. Turning 'Sync' off in the climate settings pauses the lock until 'Sync' is pressed again or the head unit restarts.",
            "The passenger and driver temperatures can be set separately, as usual.",
            null,
            null),
        new TweakInfo("driveronly", "Automatic Driver Only", "Driver Only every time the car starts; Sync once the passenger seat belt is buckled.", false, false,
            "Not recommended if you often have back-seat passengers: only the front passenger seat belt is watched, so with passengers only in the back the climate stays in Driver Only.\n\n"
            + "Each time the car is turned on, Tilepad switches the climate to Driver Only. Then Tilepad watches the passenger seat belt, checking once a second, and switches to Sync the first time the belt is buckled. The switch only goes one way: unbuckling doesn't return to Driver Only until the car is next turned on.\n\n"
            + "Drives follow the car itself, not the head unit, so a short stop counts as a new drive. If the climate is off, the change is made when you turn it on. Each step happens once per drive, so your own changes afterwards stay as you set them. The car's seat occupancy sensor isn't available to the head unit, so a passenger is only detected by the seat belt.\n\n"
            + "Takes effect immediately; no restart is needed. Turning the tweak off stops the automatic changes and leaves the climate as it is.").states(
            "The 'Driver Only' climate setting will be selected by default each time the car turns on, unless or until the front-passenger seatbelt is buckled.",
            "The climate starts each drive in whatever mode it was last left in.",
            null,
            null),
        new TweakInfo("projection", "Auto-Open Phone Projection", "Opens Android Auto or CarPlay when it becomes available.", false, false,
            "When Android Auto or Apple CarPlay becomes available, opens its Phone Projection screen using the same OEM launch action as the stock connection pop-over. Tilepad reads the underlying connection services, so this works even when Disable Right-Hand Pop-Over is enabled.\n\n"
            + "Projection opens once per connection, including a connection already available when enabled. Tilepad doesn't keep bringing projection forward after you leave it. Repeated connection notices and a restart of Tilepad's key service do not reopen an unchanged connection. A newly connected session can open projection again, and so does turning the car on while the phone is still connected. If the projected app is already on screen, it is left there. If a camera screen is on top, automatic opening waits for it to close.\n\n"
            + "Tilepad uses native connection callbacks and a lightweight status check every two seconds while enabled. Turning the tweak off stops those checks. Tilepad doesn't pair phones, enable Wi-Fi, change projection settings, or bypass the OEM's launch restrictions. Wired and wireless sessions use the same ready-state checks. Needs the exact projection interfaces of a supported firmware. Turning the tweak off stops automatic opening and leaves the current screen alone.").states(
            "Android Auto or CarPlay opens automatically when a connection becomes available.",
            "Phone Projection opens only when you or the stock system choose it.", null, null),
        new TweakInfo("chargeaway", "Charge-Away", "Turns off charging schedules away from Home, for a full charge at public chargers or while traveling.", true, false,
            "Turns off Scheduled Charging when you're away from Home and turns it back on when you return. This lets you get a full charge at public chargers or while traveling, even if you normally limit charging to off-peak hours at home.\n\n"
            + "Set Home while parked there, using the button in the middle pane. You can update or clear it later. Turning this tweak off keeps your saved Home.\n\n"
            + "While on, Tilepad checks the car's GPS every ten seconds. More than 1.5 miles (2.4 km) from Home, it turns Scheduled Charging off. Within 1 mile (1.6 km), it turns Scheduled Charging back on. In between, nothing changes, so Scheduled Charging doesn't flip back and forth near the edge. If the GPS fix is missing, old or imprecise, Tilepad waits for a better one.\n\n"
            + "Only the Scheduled Charging on/off switch changes. Your charging times, 'Charge ONLY during Off-peak' choice and departure settings stay as you set them in EV Settings, so set those up there first.\n\n"
            + "While this is on, turning Scheduled Charging on or off by hand is corrected at the next check. To manage it yourself, turn this tweak off; Scheduled Charging is then left as it is. If the car doesn't confirm a change after three tries, Tilepad stops asking until something changes.\n\n"
            + "Works only with the exact EV Settings app of a supported firmware.").states(
            "Scheduled Charging turns off more than 1.5 miles from Home and back on within 1 mile.",
            "Scheduled Charging is left as it is.", null, null),
        new TweakInfo("ota", "Disable OTA Client", "Stops the head unit's OTA client from checking for or downloading updates.", false, false,
            "Disables the head unit's OTA update client. While disabled, it cannot check for new updates, download them or show its update prompts. Takes effect immediately; no restart is needed.\n\n"
            + "Turning this tweak off or uninstalling Tilepad reenables the client, even if it was already disabled when you turned this on. Updating Tilepad keeps it disabled.\n\n"
            + "An update already downloaded or staged may still be installed by the separate update agent. This does not disable modem updates or vehicle updates handled outside the head unit. USB update and recovery components are left in place.").states(
            "The head unit's OTA client is disabled, including its update checks, downloads and prompts.",
            "The head unit's OTA client can check for and download updates as usual.", null, null)
    };
    /** The left pane's last entry, not a tweak: this head unit's software, and a log for reporting a problem. */
    static final TweakInfo SUPPORT = new TweakInfo("support", "Support", "Checks for updates, and saves a log to a USB drive for reporting a problem.", false, false,
        "Check for Updates asks GitHub for the latest Tilepad release, over Wi-Fi only: never the car's built-in modem. Installing takes a minute or two and then restarts the head unit, so the screen, including the rearview camera, is off for a minute or two. Do it while parked. Tilepad's settings and tweaks stay as they are.\n\n"
        + "Other NEMESIS packages that publish releases on GitHub can update here too. Under Configure Updates for Other Software, type the GitHub user and repository a package comes from; each package in its latest release that's installed here gets linked. Check for Updates then lists them under Tilepad: tick the ones to install, and ⓘ shows a release's notes. They install one after another, the head unit restarts once at the end, and each download must match the checksum GitHub publishes for it. Only link repositories you trust: their releases install with root access.\n\n"
        + "If a tweak doesn't seem to take effect, or something happens that shouldn't, save a log while it's still going on, or as soon as you notice, before turning the car off. Attach it to a GitHub issue that says what you expected and what you saw instead. The log covers the last few minutes, so the sooner the better.\n\n"
        + "It holds this head unit's software version, the files the tweaks depend on and whether each is patched as set, Tilepad's settings and service log, which windows are on screen, which apps were opened, and the right-hand panel's and startup screen's messages about what they showed. Saving takes about a minute; keep the USB drive plugged in.\n\n"
        + "It leaves out your location and Home, and drops messages about media, calls, contacts, radio and driver profile names. Long numbers such as phone numbers are masked.");
    /** The left pane: every tweak except companions, which show inside their partner's entry, then Support. */
    static final TweakInfo[] RAIL = rail();
    private static TweakInfo[] rail() {
        java.util.List<TweakInfo> list = new java.util.ArrayList<>();
        for (TweakInfo t : ALL) if (t != NAV) list.add(t);
        list.add(SUPPORT);
        return list.toArray(new TweakInfo[0]);
    }
    private TweakInfo() { this("", "", "", false, false, ""); }
}
