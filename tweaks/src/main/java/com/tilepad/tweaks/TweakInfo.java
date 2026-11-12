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
            "The nav handle is the semicircle on the right edge of the screen that shows in most apps and opens the nav pop-over. Hiding it stops the handle from appearing at all, rather than closing it each time it shows up. Without it, use the car's physical buttons to get to navigation.\n\n"
            + "The handle is always hidden while the right-hand pop-over is disabled. With the pop-over allowed, you can hide the handle on its own. Both settings change the same system file and share one backup of the original, so changing one doesn't undo the other. Turning either on or off takes effect after the head unit restarts. If your firmware isn't supported, or another mod has already changed that file, Tilepad says so before changing anything.").states(
            "The nav handle (the semicircle on the right edge that opens the nav pop-over) is globally hidden.",
            "The nav handle, the semicircle on the right side of the screen that opens the system nav as a pop-over, shows in most apps.",
            "The nav handle will be hidden after the next head-unit restart. Until then, it still shows.",
            "The nav handle comes back after the next head-unit restart. Until then, it stays hidden.");

    /** Under the Automatic Heaters passenger-seat checkbox, whether it's on or off. */
    static final String PASSENGER_HEAT = "The passenger seat only heats once someone buckles its seat belt, even partway through a drive, so it's safe to leave on if you usually drive alone.";

    /** Charge-Away's line while it's on with no Home saved yet. */
    static final String CHARGE_AWAY_NO_HOME = "Set your current location as Home to begin. Until then, your current **Scheduled Charging** settings will be left untouched.";

    /** Every tweak, for lookups by id. */
    static final TweakInfo[] ALL = {
        new TweakInfo("dst", "Automatic Daylight Saving Time", "Keeps the head unit’s daylight-saving switch in step with your region.", true, false,
            "Turns Hyundai's own daylight-saving setting on and off for you. The time zone you picked in Hyundai's Date/Time settings stays as it is. This only adds or removes the seasonal hour, and keeps the factory daylight-saving checkbox in step.\n\n"
            + "Choose US/Canada, Europe or No daylight saving in the middle pane. US/Canada changes on the second Sunday in March and the first Sunday in November. Europe changes on the last Sundays of March and October at 01:00 UTC. The time zone alone can't tell where you live, so Arizona, Hawaii and other places without daylight saving need No daylight saving. These dates are built in and won't update themselves if the law changes.\n\n"
            + "Works offline as long as the clock is right. It checks when the head unit starts, when the clock, time zone or date changes, when the screen wakes, and at the next scheduled change. It doesn't check every minute. While it's on, it overrides daylight-saving changes you make by hand. Turning it off puts the previous setting back, if Tilepad's change is still in place. If it can't read the clock or the car's settings, this tweak's entry says so instead of guessing."),
        new TweakInfo("consent", "Skip Startup Safety Brief", "Goes straight past the confirmation screen at head-unit startup.", false, true,
            "Stops the safety brief from appearing when the head unit starts. Tilepad tells the system the brief was accepted, the same way pressing **Confirm** does, so everything else starts normally. It doesn't wait for the screen to appear and tap **Confirm** for you.\n\n"
            + "Only startup changes. The same screen is also the driver picker, so it still shows when you switch drivers, enter a driver profile's password, or after a software update. At startup the current driver stays selected, and a profile with a password isn't asked for it then.\n\n"
            + "This changes a system file on supported firmware. Tilepad checks the original file and backs it up before changing anything. Turning it on or off takes effect after the head unit fully restarts. Turning the car off and on may leave the head unit running, so the change may not apply until it actually restarts.\n\n"
            + "This only skips the reminder. It doesn't change driving restrictions, driver-assistance systems or any vehicle safety features. If another mod already changes the same file, turn that mod off first. Tilepad tells you if the file isn't one it supports instead of overwriting it.").states(
            "The safety brief is skipped, so the head unit goes straight to the home screen when it starts.",
            "The safety brief shows every time the head unit starts, and you need to press **Confirm** to dismiss it.",
            "This takes effect after the next head-unit restart. Until then, the safety brief still shows.",
            "This turns off after the next head-unit restart. Until then, the safety brief is still skipped."),
        new TweakInfo("heaters", "Automatic Heaters", "At a cold head-unit boot, switches on only the heaters you select.", true, false,
            "Once each time the head unit starts, Tilepad reads the outside temperature. If it's at or below your threshold, it turns on each heater you selected. Choose the driver seat, steering wheel and passenger seat independently. Each seat has its own level: 1 Low, 2 Medium or 3 High. Only turn on heaters your car has.\n\n"
            + "The passenger seat only heats when someone is sitting there. If it's cold enough at startup, Tilepad waits for the front passenger's seat belt to be buckled and heats the seat then, whether that's right away or later in the drive. If nobody buckles up before the car is turned off, the seat stays off. So it's safe to leave the passenger seat selected even if you usually drive alone. (The car doesn't report seat weight to the head unit, so the seat belt is the only sign of a passenger.)\n\n"
            + "The controls in the middle pane set the threshold in °F or °C, choose the heaters and set each seat's level. Switching between °F and °C only changes how the threshold is shown. If the car doesn't report a valid outside temperature within two minutes, nothing is heated that time.\n\n"
            + "Changes apply the next time the head unit starts, not right away. Restarting Tilepad's service doesn't turn the heaters on again. Turning the car off and on may not restart the head unit, so the heaters won't necessarily come on for every drive. Tilepad turns each heater on once and never fights your changes afterwards. Turning this tweak off stops it for future startups and leaves the heaters as they are. The car still has to allow heating for a heater to come on."),
        new TweakInfo("tracks", "Swap Steering-Wheel Track Buttons", "Up selects the next track; down selects the previous track.", false, true,
            "Swaps the steering-wheel track buttons so up goes to the next track and down goes back. Where the radio uses these buttons to step through stations, they're swapped the same way. The knob and other buttons are unchanged.\n\n"
            + "Tilepad changes only the two track-button entries in the key layout file the car loads, which is the same on every model. Nothing runs in the background. The car only reads key layouts when it starts, so restart the head unit after turning this on or off.\n\n"
            + "Tilepad keeps the original layouts and puts them back exactly when you turn this off. If the layout file isn't what Tilepad expects, or another mod has changed it, Tilepad won't overwrite it.").states(
            "The steering-wheel track buttons are swapped: up skips to the next track, and down goes back.",
            "The steering-wheel track buttons work Hyundai's default way: up goes back a track, and down skips ahead.",
            "The buttons will be swapped after the next head-unit restart. Until then, they still work the default way.",
            "The buttons go back to Hyundai's default after the next head-unit restart. Until then, they stay swapped."),
        new TweakInfo("popover", "Disable Right-Hand Pop-Over", "Stops transient media and information panels covering your screen.", false, true,
            "Stops the panel that slides in on the right side of the screen, for example when the track changes. This includes the media and information pop-over and the notices that physical buttons force onto it. It's blocked before it shows, not closed after it covers your screen. Disabling the pop-over also hides the nav handle.\n\n"
            + "The car's Display settings have a Media change notifications option, but it still lets button presses show notices. This tweak blocks those too, and leaves your Display setting as it was for when you turn the tweak off.\n\n"
            + "This blocks everything that uses that right-hand panel, not just track changes. It doesn't affect the HUD, media playback, full-screen navigation or the reversing camera.\n\n"
            + "Works only with supported firmware. Restart the head unit after turning it on or off. Tilepad backs up the original file, and turning this off restores it while keeping your nav handle choice. If you already have another mod that changes this panel, turn it off before turning this one on.\n\n"
            + NAV.help).states(
            "Media and information pop-overs are blocked, so they no longer cover the right side of the screen.",
            "Media and information pop-overs appear on the right side of the screen, for example when the track changes.",
            "Pop-overs will be blocked after the next head-unit restart. Until then, they still appear.",
            "Pop-overs come back after the next head-unit restart. Until then, they stay blocked.")
            .with("Right-Hand Pop-Over Settings", NAV),
        NAV,
        new TweakInfo("sync", "Maintain Climate Sync", "Locks the passenger temperature to yours, so the two sides never differ.", false, false,
            "Keeps the driver and passenger temperatures together. When the passenger temperature is changed, with the passenger buttons or the climate screen, the driver temperature is moved to the same setting and **Sync** is turned straight back on. Either side's buttons then adjust the whole cabin. If the driver temperature is changed while the driver side is still catching up, the driver's new setting wins and the passenger side follows it.\n\n"
            + "Turning **Sync** off yourself in the climate settings pauses the lock, so you can set the two sides apart when you want to. The lock resumes when **Sync** is pressed again or the head unit restarts.\n\n"
            + "The passenger buttons are handled by the climate controller itself, not the head unit, so Tilepad follows each press rather than replacing it: you may see the two temperatures differ for a moment and **Sync** blink off and on. The driver temperature is moved one step at a time, the same as pressing its own buttons.\n\n"
            + "Driver Only is left alone, so this works alongside Automatic Driver Only. Takes effect immediately; no restart is needed. Turning the tweak off leaves the climate as it is.").states(
            "The passenger and driver temperatures are locked together, and **Sync** stays on. Turning **Sync** off in the climate settings pauses the lock until **Sync** is pressed again or the head unit restarts.",
            "The passenger and driver temperatures can be set separately, as usual.",
            null,
            null),
        new TweakInfo("driveronly", "Automatic Driver Only", "Starts every drive with the **Driver Only** climate toggled enabled as long as there is no passenger present.", false, false,
            "Not recommended if you often have back-seat passengers: only the front passenger seat belt is watched, so with passengers only in the back the climate stays in Driver Only.\n\n"
            + "Each time the car is turned on, Tilepad switches the climate to **Driver Only**. Then Tilepad watches the passenger seat belt, checking once a second, and switches to **Sync** the first time the belt is buckled. The switch only goes one way: unbuckling doesn't return to **Driver Only** until the car is next turned on.\n\n"
            + "Drives follow the car itself, not the head unit, so a short stop counts as a new drive. If the climate is off, the change is made when you turn it on. Each step happens once per drive, so your own changes afterwards stay as you set them. The car's seat occupancy sensor isn't available to the head unit, so a passenger is only detected by the seat belt.\n\n"
            + "Takes effect immediately; no restart is needed. Turning the tweak off stops the automatic changes and leaves the climate as it is.").states(
            "The **Driver Only** climate setting will be selected by default each time the car turns on, unless or until the front-passenger seatbelt is buckled.",
            "The climate starts each drive in whatever mode it was last left in.",
            null,
            null),
        new TweakInfo("projection", "Auto-Open Phone Projection", "Opens Android Auto or CarPlay when it becomes available.", false, false,
            "When Android Auto or Apple CarPlay becomes available, opens it the same way the car's own connection pop-over does. Tilepad watches the connection itself, so this works even with Disable Right-Hand Pop-Over turned on.\n\n"
            + "It opens once per connection, including a phone that's already connected when you turn this on. If you leave Android Auto or CarPlay, Tilepad doesn't keep bringing it back. A new connection opens it again, and so does turning the car on while the phone is still connected. If it's already on screen, it's left alone. If a camera view is showing, Tilepad waits for it to close.\n\n"
            + "While this is on, Tilepad listens for connection changes and checks the connection every two seconds. It doesn't pair phones, turn on Wi-Fi, change projection settings or get around the car's own launch restrictions. Wired and wireless connections work the same way. Works only with supported firmware. Turning this off stops it opening automatically and leaves the current screen alone.").states(
            "Android Auto or CarPlay opens automatically when a connection becomes available.",
            "**Phone Projection** opens only when you or the stock system choose it.", null, null),
        new TweakInfo("chargeaway", "Charge-Away", "Turns off charging schedules away from Home, for a full charge at public chargers or while traveling.", true, false,
            "Turns off **Scheduled Charging** when you're away from Home and turns it back on when you return. This lets you get a full charge at public chargers or while traveling, even if you normally limit charging to off-peak hours at home.\n\n"
            + "Set Home while parked there, using the button in the middle pane. You can update or clear it later. Turning this tweak off keeps your saved Home.\n\n"
            + "While on, Tilepad checks the car's GPS every ten seconds. More than 1.5 miles (2.4 km) from Home, it turns **Scheduled Charging** off. Within 1 mile (1.6 km), it turns **Scheduled Charging** back on. In between, nothing changes, so **Scheduled Charging** doesn't flip back and forth near the edge. If the GPS fix is missing, old or imprecise, Tilepad waits for a better one.\n\n"
            + "Only the **Scheduled Charging** on/off switch changes. Your charging times, **Charge ONLY during Off-peak** choice and departure settings stay as you set them in **EV Settings**, so set those up there first.\n\n"
            + "While this is on, turning **Scheduled Charging** on or off by hand is corrected at the next check. To manage it yourself, turn this tweak off, and your current **Scheduled Charging** settings will be left untouched. If the car doesn't confirm a change after three tries, Tilepad stops asking until something changes.\n\n"
            + "Works only with the exact EV Settings app of a supported firmware.").states(
            "**Scheduled Charging** turns off more than 1.5 miles from Home and back on within 1 mile.",
            "Your current **Scheduled Charging** settings will be left untouched.", null, null),
        new TweakInfo("ota", "Disable OTA Client", "Stops the head unit's OTA client from checking for or downloading updates.", false, false,
            "Disables the head unit's OTA update client. While disabled, it cannot check for new updates, download them or show its update prompts. Takes effect immediately; no restart is needed.\n\n"
            + "Turning this tweak off or uninstalling Tilepad turns the client back on, even if it was already disabled when you turned this on. Updating Tilepad keeps it disabled.\n\n"
            + "An update already downloaded or staged may still be installed by the separate update agent. This does not disable modem updates or vehicle updates handled outside the head unit. USB update and recovery components are left in place.").states(
            "The head unit's OTA client is disabled, including its update checks, downloads and prompts.",
            "The head unit's OTA client can check for and download updates as usual.", null, null)
    };
    /** A left-pane entry after the tweaks, not a tweak: this head unit's software, and a log for reporting a problem. */
    static final TweakInfo SUPPORT = new TweakInfo("support", "Support", "Saves a log to a USB drive for reporting a problem.", false, false,
        "If a tweak doesn't seem to take effect, or something happens that shouldn't, save a log while it's still going on, or as soon as you notice, before turning the car off. Attach it to a GitHub issue that says what you expected and what you saw instead. The log covers the last few minutes, so the sooner the better.\n\n"
        + "It holds this head unit's software version, the files the tweaks depend on and whether each is patched as set, Tilepad's settings and service log, which windows are on screen, which apps were opened, and the right-hand panel's and startup screen's messages about what they showed. Saving takes about a minute; keep the USB drive plugged in.\n\n"
        + "It leaves out your location and Home, and drops messages about media, calls, contacts, radio and driver profile names. Long numbers such as phone numbers are masked.");
    /** The left pane's last entry, not a tweak: installs and updates Tilepad and the packages linked to GitHub. */
    static final TweakInfo UPDATES = new TweakInfo("updates", "Package Manager", "Installs and updates Tilepad and linked NEMESIS packages straight from GitHub.", false, false,
        "Package Manager installs and updates NEMESIS packages straight from their developers' releases on GitHub, including ones that aren't in the NEMESIS Package Store. It lists only Tilepad and the packages you've linked to a GitHub repository, with their versions. A package installed from NEMESIS Manager or USB and never linked isn't touched: NEMESIS Manager keeps updating it.\n\n"
        + "Check for Updates asks GitHub for the latest releases, over Wi-Fi only: never the car's built-in modem. When everything is current, each version says (latest). When there are updates, tick the ones to install (ⓘ shows a release's notes), then Install. They install one after another, then the head unit restarts once, so the screen, including the rearview camera, is off for a minute or two. Do it while parked. Tilepad's settings and tweaks stay as they are. Untick everything to dismiss the updates instead.\n\n"
        + "Configure Other Software links a new package: type the GitHub user or organization it comes from, or pick one you looked up before, then pick its repository. Its repositories that mention NEMESIS are listed; Show All lists the rest. Before anything changes, it says what linking does: a package that's already installed on the car is only linked, and one that isn't gets installed, then updates the same way.\n\n"
        + "The bin beside a package uninstalls it, or only unlinks it so it stays installed and Package Manager stops updating it.\n\n"
        + "Every download must match the checksum GitHub publishes for it, and Tilepad's must also carry its release signature. Only link repositories you trust: their releases install with root access.");
    /** The left pane: every tweak except companions, which show inside their partner's entry, then Support and Package Manager. */
    static final TweakInfo[] RAIL = rail();
    private static TweakInfo[] rail() {
        java.util.List<TweakInfo> list = new java.util.ArrayList<>();
        for (TweakInfo t : ALL) if (t != NAV) list.add(t);
        list.add(SUPPORT);
        list.add(UPDATES);
        return list.toArray(new TweakInfo[0]);
    }
    private TweakInfo() { this("", "", "", false, false, ""); }
}
