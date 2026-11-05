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

    /** Every tweak, for lookups by id. */
    static final TweakInfo[] ALL = {
        new TweakInfo("dst", "Automatic Daylight Saving Time", "Keeps the head unit’s daylight-saving switch in step with your region.", true, false,
            "Automatically turns Hyundai’s own daylight-saving setting on and off. The base timezone you selected in Hyundai Date/Time settings stays in place; this adds the seasonal hour when appropriate. It also keeps the factory daylight-saving checkbox in step.\n\n"
            + "Choose US/Canada, Europe, or No daylight saving in the middle pane. US/Canada uses the second Sunday in March and first Sunday in November. Europe uses the last Sunday in March and October at 01:00 UTC. A fixed timezone offset does not identify where you live: Arizona, Hawaii and other exceptions need No daylight saving. These schedules are explicit rules; they do not update themselves if the law changes.\n\n"
            + "Works offline with an accurate clock. It checks at head-unit boot, clock/timezone/date changes, waking the display, and the next scheduled transition. There is no minute-by-minute polling. While enabled it overrides manual daylight-saving changes. Disabling restores the previous setting where Tilepad still owns it. A failed clock/service check is shown here instead of guessing."),
        new TweakInfo("consent", "Skip Startup Safety Brief", "Goes straight past the confirmation screen at head-unit startup.", false, true,
            "Prevents the normal startup confirmation screen from being constructed. It uses the factory agreement path so the rest of the system receives its normal agreed flags and broadcast. It does not wait for the screen to appear and press Confirm.\n\n"
            + "Changes the captured, supported SystemUI optimized code. The original file is verified and backed up before any change. Enabling and disabling take effect after a full head-unit restart. A car off/on cycle may leave the head unit running and may not apply the change.\n\n"
            + "This skips the reminder only; it does not alter driving restrictions, driver-assistance systems, or vehicle safety features. If another mod already changes SystemUI, switch that mod off first. The checkbox reports an unsupported file instead of overwriting it.").states(
            "The safety brief is skipped, so the head unit goes straight to the home screen when it starts.",
            "The safety brief shows every time the head unit starts, and you need to press 'Confirm' to dismiss it.",
            "This takes effect after the next head-unit restart. Until then, the safety brief still shows.",
            "This turns off after the next head-unit restart. Until then, the safety brief is still skipped."),
        new TweakInfo("heaters", "Automatic Heaters", "At a cold head-unit boot, switches on only the heaters you select.", true, false,
            "Once per head-unit boot, reads the outdoor temperature and, only when it is strictly below your threshold, sends one request to each selected heater. Choose the driver seat, steering wheel and passenger seat independently. Each seat has its own level: 1 Low, 2 Medium, 3 High. Only enable equipment your vehicle has.\n\n"
            + "The controls in the middle pane set the threshold in °F or °C, select heaters and set seat intensity. Unit selection changes how the threshold is displayed, not how the vehicle sensor is interpreted. No valid temperature means no heat command: it waits for up to two minutes, rejects unavailable/uninitialized sensor values, and then skips that boot. At the exact threshold it does nothing.\n\n"
            + "Enabling or changing settings applies at the next head-unit boot, not immediately. Restarting Tilepad’s service does not repeat the requests. Turning the car off/on may not reboot the head unit, so this does not necessarily run on every drive. It never keeps forcing a level after you change the controls. Disabling stops future automation and leaves current heater settings for you to adjust. An accepted request still needs the normal vehicle conditions for heating to work."),
        new TweakInfo("tracks", "Swap Steering-Wheel Track Buttons", "Up selects the next track; down selects the previous track.", false, true,
            "Reverses the steering-wheel track-up and track-down mappings so up means next and down means previous. Radio channel stepping follows the same reversed mapping where the stock system uses these keys. Knob movement and other buttons keep their own mappings.\n\n"
            + "Changes only the two track-key entries in the verified IONIQ 5 key layouts. It uses no input grab, injected keys, or background listener. Android reads key layouts when devices initialize, so restart the head unit after enabling or disabling.\n\n"
            + "The original layouts are retained and restored exactly when disabled. If the layout differs or another remapping mod owns it, Tilepad refuses to overwrite it. The final direction must be verified on the car with both media and radio.").states(
            "The steering-wheel track buttons are swapped: up skips to the next track, and down goes back.",
            "The steering-wheel track buttons work Hyundai's default way: up goes back a track, and down skips ahead.",
            "The buttons will be swapped after the next head-unit restart. Until then, they still work the default way.",
            "The buttons go back to Hyundai's default after the next head-unit restart. Until then, they stay swapped."),
        new TweakInfo("popover", "Disable Right-Hand Pop-Over", "Stops transient media and information panels covering your screen.", false, true,
            "Blocks the shared right-side OSD panel before it can display. This includes the temporary media/information pop-over and forced requests using that same panel. It blocks the request path and shared renderer, rather than closing the panel after it covers your screen. Disabling the pop-over also hides the nav handle.\n\n"
            + "The factory Display settings include Media change notifications, but that option still allows notices triggered by physical buttons. This tweak also blocks those forced requests. It leaves your factory media-notification preference in place for when you turn the tweak off.\n\n"
            + "This is a broad right-panel switch: other notices using the same OSD panel will also be hidden, not only track changes. It does not intentionally change the HUD, media playback, full-screen navigation or the reversing-camera app. Their behavior still needs a parked-car check because the emulator cannot reproduce the complete OEM window system.\n\n"
            + "The first draft supports only the exact captured RightScreenHome files. Restart the head unit after enabling or disabling. The original code is backed up, and disabling restores it while preserving the handle setting. If you already have another panel mod installed, turn it off before enabling this one.\n\n"
            + NAV.help).states(
            "Media and information pop-overs are blocked, so they no longer cover the right side of the screen.",
            "Media and information pop-overs appear on the right side of the screen, for example when the track changes.",
            "Pop-overs will be blocked after the next head-unit restart. Until then, they still appear.",
            "Pop-overs come back after the next head-unit restart. Until then, they stay blocked.")
            .with("Right-Hand Pop-Over Settings", NAV),
        NAV,
        new TweakInfo("sync", "Maintain Climate Sync", "Locks the passenger temperature to yours, so the two sides never differ.", false, false,
            "Keeps the driver and passenger temperatures together. When the passenger temperature is changed, with the passenger buttons or the climate screen, the driver temperature is moved to the same setting and Sync is turned straight back on. Either side's buttons then adjust the whole cabin. If the driver temperature is changed while the driver side is still catching up, the driver's new setting wins and the passenger side follows it.\n\n"
            + "Turning Sync off yourself in the climate settings pauses this, so you can set the two sides apart when you want to. It resumes when Sync is pressed again or the head unit restarts.\n\n"
            + "The passenger buttons are handled by the climate controller itself, not the head unit, so this follows each press rather than replacing it: you may see the two temperatures differ for a moment and Sync blink off and on. The driver temperature is moved one step at a time, the same as pressing its own buttons.\n\n"
            + "Driver Only is left alone, so it works with Automatic Driver Only. Takes effect immediately; no restart is needed. Turning it off leaves the climate as it is.").states(
            "The passenger and driver temperatures are locked together, and 'Sync' always stays on. Manually disabling 'Sync' in the climate settings will temporarily disable this functionality until 'Sync' is pressed again or the system reboots.",
            "The passenger and driver temperatures can be set separately, as usual.",
            null,
            null),
        new TweakInfo("driveronly", "Automatic Driver Only", "Driver Only every time the car starts; Sync once the passenger seat belt is buckled.", false, false,
            "Not recommended if you often have back-seat passengers: only the front passenger seat belt is watched, so with passengers only in the back the climate stays in Driver Only.\n\n"
            + "Each time the car is turned on, switches the climate to Driver Only. Then it watches the passenger seat belt, checking once a second, and switches to Sync the first time it is buckled. This only goes one way: unbuckling doesn't return to Driver Only until the car is next turned on.\n\n"
            + "It follows the car itself, not the head unit, so a short stop counts as a new drive. If the climate is off, the change is made when you turn it on. Each step happens once per drive, so your own changes afterwards stay as you set them. The car's seat occupancy sensor isn't available to the head unit, so a passenger is only detected by the seat belt.\n\n"
            + "Takes effect immediately; no restart is needed. Turning it off stops the automatic changes and leaves the climate as it is.").states(
            "The 'Driver Only' climate setting will be selected by default each time the car turns on, unless or until the front-passenger seatbelt is buckled.",
            "The climate starts each drive in whatever mode it was last left in.",
            null,
            null),
        new TweakInfo("projection", "Auto-Open Phone Projection", "Opens Android Auto or CarPlay when it becomes available.", false, false,
            "When Android Auto or Apple CarPlay becomes available, opens its Phone Projection screen using the same OEM launch action as the stock connection pop-over. It reads the underlying connection services, so it works even when Disable Right-Hand Pop-Over is enabled.\n\n"
            + "It opens once per connection, including a connection already available when enabled. It does not keep bringing projection forward after you leave it. Repeated connection notices and a restart of Tilepad's key service do not reopen an unchanged connection. A newly connected session can open it again. If the projected app is already on screen, it is left there. If a camera screen is on top, automatic opening waits for it to close.\n\n"
            + "It does not pair phones, enable Wi-Fi, change projection settings, or bypass the OEM's launch restrictions. Wired and wireless sessions use the same ready-state checks. This first version requires the exact captured IONIQ 5 projection interfaces. Turning it off stops automatic opening and leaves the current screen alone.").states(
            "Android Auto or CarPlay opens automatically when a connection becomes available.",
            "Phone Projection opens only when you or the stock system choose it.", null, null),
        new TweakInfo("chargeaway", "Charge-Away", "Scheduled Charging at Home; unrestricted charging when away.", true, false,
            "Save your current location as Home using the button in the middle pane. Its latitude and longitude are saved on the head unit and shown here. Mark Home while parked at the location you want to use. You can replace it with the same button later; turning this tweak off keeps Home.\n\n"
            + "While enabled with Home set, checks the vehicle's existing GPS fix every ten seconds. Beyond 1.5 miles (2.41 km), turns Scheduled Charging off. Within 1 mile (1.61 km), turns it back on. Between those distances, keeps the last Home/Away state to prevent repeated changes near a boundary. That state survives a restart. Before either boundary has been established, it leaves scheduling unchanged.\n\n"
            + "It changes only the Scheduled Charging master mode. Your Charge ONLY during Off-peak choice, charging times and departure/climate settings are not edited. On returning Home it reads the stock saved off-peak choice, so it restores the mode you selected. Configure off-peak times and select Charge ONLY during Off-peak yourself in EV Settings if you want strict time-of-use charging. It cannot guarantee a charge completion time or electricity rate.\n\n"
            + "Missing, stale (over 60 seconds), or inaccurate (over 150 metres) GPS pauses changes. The entire accuracy circle must cross a boundary before switching. While enabled, a manual change to Scheduled Charging is corrected at the next valid check if it conflicts with the current Home/Away state. It sends a request only when needed, checks vehicle feedback, and stops retrying after three unconfirmed requests until the state or configuration changes, or the runtime restarts.\n\n"
            + "Turning this tweak off stops the location checks and leaves Scheduled Charging in its current state; it does not restore an earlier setting. The saved location stays local in Tilepad's protected configuration. This first version requires the exact captured IONIQ 5 EV Settings interface.").states(
            "Scheduled Charging follows your saved Home location. Set Home below before the first use.",
            "Scheduled Charging is left as it is. Your saved Home location is kept.", null, null)
    };
    /** The left pane: every tweak except companions, which show inside their partner's entry. */
    static final TweakInfo[] RAIL = rail();
    private static TweakInfo[] rail() {
        java.util.List<TweakInfo> list = new java.util.ArrayList<>();
        for (TweakInfo t : ALL) if (t != NAV) list.add(t);
        return list.toArray(new TweakInfo[0]);
    }
    private TweakInfo() { this("", "", "", false, false, ""); }
}
