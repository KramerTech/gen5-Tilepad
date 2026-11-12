package com.tilepad.tweaks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.tilepad.tweakcore.Config;
import com.tilepad.tweakcore.Platform;
import com.tilepad.tweakcore.SelfUpdate;
import java.util.*;
import java.util.concurrent.*;

/** Three independently scrolling panes, using the installed EV Settings assets when available. */
public final class TweaksActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Properties values = new Properties();
    /** unavailable.<id> from one background compatibility audit after the settings load. */
    private final Properties audit = new Properties();
    private boolean audited;
    /** Until that audit answers, a tweak can't be turned on (it might be unavailable here). */
    private boolean checking = true;
    private Resources stock;
    /** Device Connections' app, for its buttons (Add New Device): the EV app's are an older, flat style. Null if missing. */
    private Resources buttons;
    private static final String BUTTONS = "com.android.btsettings";
    private LinearLayout controls;
    private ScrollView railScroll, controlScroll, helpScroll;
    private boolean preview, busy = true, ready;
    private String error;
    private boolean capturingHome;
    /** Why the compatibility report couldn't be saved, until another tweak is selected. */
    private String reportNotice;
    /** The report file on the USB drive: saved here, or found there by the audit. */
    private String reportSaved = "";
    /** The support log saved from Support this session. */
    private String supportSaved = "";
    private boolean savingReport;
    /** Configure Other Software, while it's open. */
    private AlertDialog sourcesDialog;
    /** Package Manager's Check for Updates, this session: how far it got, and what it found (Tilepad, then linked packages). */
    private enum Step { NONE, CHECKING, CHECKED, DOWNLOADING, INSTALLING }
    private Step step = Step.NONE;
    private List<Updater.Offer> offers = new ArrayList<>();
    /** The ids ticked for install. */
    private final Set<String> chosen = new HashSet<>();
    /** What Install downloads, and which one it's on and how far. */
    private List<Updater.Offer> installing = new ArrayList<>();
    private int downloading, downloaded = -1;
    /** Why the last check or install didn't work, until the next one. */
    private String updateNotice;
    private final SettingsEdits edits = new SettingsEdits();
    private final Runnable saveFlush = new Runnable() { @Override public void run() { flushEdits(); } };
    private int selected, bg, ink, muted, line, accent = 0xff00b5de;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        selected = getPreferences(0).getInt("selected", 0);
        if (selected < 0 || selected >= TweakInfo.RAIL.length) selected = 0;
        preview = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
            && getIntent().getBooleanExtra("design_preview", false);
        if (preview) {
            try { values.load(new java.io.StringReader(getPreferences(0).getString("preview", ""))); } catch (Exception ignored) { }
        }
        try { stock = getPackageManager().getResourcesForApplication("com.hkmc.daudio.app.ev3"); } catch (Exception ignored) { }
        edits.reset(values); render(); refresh();
    }
    @Override protected void onResume() { super.onResume(); if (ready && !busy && !edits.hasWork()) refresh(); main.post(liveRefresh); }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); render(); }
    @Override protected void onPause() {
        main.removeCallbacks(saveFlush); main.removeCallbacks(liveRefresh);
        if (edits.hasWork()) flushEdits();
        super.onPause();
    }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy(); }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private int stockId(String type, String name) { return stock == null ? 0 : stock.getIdentifier(name, type, "com.hkmc.daudio.app.ev3"); }
    private int color(String name, int fallback) {
        try { int id = stockId("color", name); return id == 0 ? fallback : stock.getColor(id); } catch (Exception ignored) { return fallback; }
    }
    private Drawable drawable(String name, Drawable fallback) {
        try { int id = stockId("drawable", name); return id == 0 ? fallback : stock.getDrawable(id); } catch (Exception ignored) { return fallback; }
    }
    private TextView text(String label, float size, int color) {
        TextView v = new TextView(this); v.setText(label); v.setTextColor(color); v.setTextSize(size);
        v.setGravity(Gravity.CENTER_VERTICAL); return v;
    }
    private ScrollView scroller(String description) {
        ScrollView s = new ScrollView(this); s.setFillViewport(true); s.setContentDescription(description);
        s.setScrollbarFadingEnabled(false); return s;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private void render() {
        values = edits.display();
        // Fetched per render, so a day/night switch picks up the matching art.
        try { buttons = getPackageManager().getResourcesForApplication(BUTTONS); } catch (Exception e) { buttons = null; }
        final boolean firstLayout = railScroll == null || railScroll.getHeight() == 0;
        final View[] selectedTab = new View[1];
        final int railY = firstLayout ? 0 : railScroll.getScrollY();
        final int controlY = controlScroll == null ? 0 : controlScroll.getScrollY();
        final int helpY = helpScroll == null ? 0 : helpScroll.getScrollY();
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(night ? android.R.style.Theme_Holo_NoActionBar : android.R.style.Theme_Holo_Light_NoActionBar);
        bg = night ? 0xff171b1f : 0xffefefef;
        ink = color("text_eco_input_normal", night ? 0xffb6c1c8 : 0xff62797f);
        muted = night ? 0xffb6c1c8 : 0xff6a6a6a; line = night ? 0xff41494e : 0xffc7c7c7;
        LinearLayout root = column(); root.setBackgroundColor(bg); setContentView(root);
        FrameLayout header = new FrameLayout(this);
        TextView title = text("Tweaks", 28, ink); title.setTypeface(null, Typeface.BOLD); title.setGravity(Gravity.CENTER);
        header.addView(title, new FrameLayout.LayoutParams(-1, -1));
        String notice = preview ? "Preview" : edits.hasWork() ? "Saving…" : busy ? (capturingHome || savingReport ? "Saving…" : step == Step.CHECKING ? "Checking…" : updating() ? "Updating…" : "Reading settings…") : "true".equals(values.getProperty("restart")) ? "Restart required" : "";
        TextView state = text(notice, 16, ink); state.setPadding(dp(12), 0, dp(22), 0); state.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        header.addView(state, new FrameLayout.LayoutParams(-1, -1));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(60))); divider(root);
        LinearLayout panes = new LinearLayout(this); root.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
        // Match the stock 250 / 600 / 426 dp split and scale down together on smaller screens.
        float width = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        float scale = Math.min(1f, width / 1278f);
        railScroll = scroller("Tweak names"); LinearLayout rail = column(); railScroll.addView(rail);
        panes.addView(railScroll, new LinearLayout.LayoutParams(dp(250 * scale), -1)); verticalDivider(panes);
        for (int i = 0; i < TweakInfo.RAIL.length; i++) {
            final int index = i; TweakInfo item = TweakInfo.RAIL[i];
            TextView tab = text(item.railTitle(), 26, i == selected ? accent : ink);
            // Support and Package Manager aren't tweaks: a mark sets each apart.
            if (item == TweakInfo.SUPPORT || item == TweakInfo.UPDATES) {
                int c = i == selected ? accent : ink;
                tab.setCompoundDrawablesWithIntrinsicBounds(item == TweakInfo.SUPPORT ? helpMark(c, dp(28)) : updateMark(c, dp(28)), null, null, null);
                tab.setCompoundDrawablePadding(dp(12));
            }
            tab.setPadding(dp(14), dp(10), dp(14), dp(10)); tab.setMinHeight(dp(72));
            if (i == selected) selectedTab[0] = tab;
            tab.setSelected(i == selected); tab.setFocusable(true); tab.setClickable(true);
            // The capture or update belongs to this pane: the others wait until it finishes.
            if ((savingReport || updating()) && i != selected) { tab.setEnabled(false); tab.setAlpha(.4f); }
            tab.setBackground(drawable("bt_list_selector", selector(i == selected)));
            tab.setContentDescription(item.railTitle() + (i == selected ? ", selected" : ""));
            tab.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                if (selected == index || savingReport || updating()) return;
                selected = index; reportNotice = null; if (ready) error = null; getPreferences(0).edit().putInt("selected", selected).apply();
                controlScroll.scrollTo(0, 0); helpScroll.scrollTo(0, 0); render();
            } });
            rail.addView(tab, new LinearLayout.LayoutParams(-1, -2)); divider(rail);
        }
        controlScroll = scroller("Selected tweak controls"); controls = column(); controlScroll.addView(controls);
        panes.addView(controlScroll, new LinearLayout.LayoutParams(dp(600 * scale), -1)); verticalDivider(panes);
        final TweakInfo item = TweakInfo.RAIL[selected];
        if (item == TweakInfo.SUPPORT) support(); else if (item == TweakInfo.UPDATES) packageManager(); else tweak(item);
        helpScroll = scroller("Feature explanation"); LinearLayout help = column(); helpScroll.addView(help);
        panes.addView(helpScroll, new LinearLayout.LayoutParams(0, -1, 1));
        TextView description = text(item.help, 24, ink); description.setGravity(Gravity.TOP); description.setLineSpacing(dp(3), 1f);
        description.setPadding(dp(30), dp(24), dp(30), dp(30)); help.addView(description, new LinearLayout.LayoutParams(-1, -2));
        final ScrollView railView = railScroll, controlView = controlScroll, helpView = helpScroll;
        railView.post(new Runnable() { @Override public void run() { railView.scrollTo(0, firstLayout ? selectedTab[0].getTop() : railY); controlView.scrollTo(0, controlY); helpView.scrollTo(0, helpY); } });
    }
    /** The middle pane for a tweak: its checkbox, state, compatibility issue and settings. */
    private void tweak(TweakInfo item) {
        String unavailable = unavailable(item.id);
        String saved = reportSaved.isEmpty() ? audit.getProperty("report", "") : reportSaved;
        boolean enabled = new Config(values).enabled(item.id);
        // No heater fitted at all (this trim): say so instead of offering the tweak; no log can add it.
        boolean absent = item.id.equals("heaters") && "false".equals(audit.getProperty("fitted.driver"))
            && "false".equals(audit.getProperty("fitted.passenger")) && "false".equals(audit.getProperty("fitted.wheel"));
        if (absent) note("This car doesn't have heated seats or a heated steering wheel, so this tweak doesn't apply.", false);
        else checkbox(controls, item.title, item.id, enabled, ready && !busy && (enabled || (!checking && unavailable.isEmpty())));
        if (!absent && !enabled && checking && (ready || busy)) progress("Checking compatibility, please wait…");
        // A compatibility issue replaces everything else below the checkbox: only the issue, then the
        // log button or where the log went.
        boolean blocked = !unavailable.isEmpty();
        String runtime = absent || blocked ? "" : liveStatus(item.id);
        String described = ready && !absent && !blocked ? item.state(enabled, pending(item.id)) : null;
        if (ready && enabled && !blocked && item.id.equals("chargeaway") && !new Config(values).hasHome()) described = TweakInfo.CHARGE_AWAY_NO_HOME;
        if (described != null) note(described, false);
        // Then what the tweak is doing right now, if it says.
        if (!runtime.isEmpty() && (described == null || enabled)) live(runtime);
        // A refused save; when this tweak has a compatibility issue, the issue below already says why.
        if (error != null && !blocked) note(compatibility(error) ? "Compatibility issue: " + error : error, true);
        if (!unavailable.isEmpty() && saved.isEmpty()) note("Compatibility issue: " + unavailable, true);
        if (item.companion != null && !blocked) {
            // The nav handle: forced hidden while the pop-over is disabled, otherwise its own choice.
            TweakInfo second = item.companion;
            String secondUnavailable = unavailable(second.id);
            boolean hidden = enabled || new Config(values).enabled(second.id);
            checkbox(controls, second.title, second.id, hidden, ready && !busy && !enabled && (hidden || (!checking && secondUnavailable.isEmpty())));
            String secondState = !ready ? null : enabled && !pending(second.id)
                ? "The nav handle is always hidden while the right-hand pop-over is disabled." : second.state(hidden, pending(second.id));
            if (secondState != null) note(secondState, false);
            if (!secondUnavailable.isEmpty() && !enabled && saved.isEmpty()) note("Compatibility issue: " + secondUnavailable, true);
        }
        // Firmware this build wasn't made for: offer the details needed to add it.
        boolean incompatible = !unavailable.isEmpty()
            || (item.companion != null && !unavailable(item.companion.id).isEmpty());
        if (ready && incompatible && !saved.isEmpty()) {
            note("Log saved to the USB drive as " + saved + ". To help add compatibility, open a new issue at "
                + "github.com/KramerTech/gen5-Tilepad/issues on a computer and attach that .zip file.", false);
        } else if (ready && incompatible) {
            if (reportNotice != null) note(reportNotice, true);
            if (savingReport) progress("Capturing the log. This can take a minute; keep the USB drive plugged in.");
            Button report = button(savingReport ? "Capturing Log…" : "Capture Compatibility Log to USB", "Save this head unit's firmware details to the USB drive", new View.OnClickListener() {
                @Override public void onClick(View v) { saveReport(false); }
            });
            report.setTextSize(22); enable(report, ready && !busy && !edits.hasWork());
            action(report); divider(controls);
        }
        if (!blocked && !values.getProperty("runtime", "").isEmpty()) note(values.getProperty("runtime"), true);
        if (!blocked && item.id.equals("chargeaway")) {
            // Two columns: with no Home, what's missing and the button that sets it; with one, update and clear side by side.
            boolean home = new Config(values).hasHome();
            Button mark = button(home ? "Update to Current Location" : "Set Current Location as Home",
                home ? "Replace Home with the current GPS location" : "Save the current GPS location as Home", new View.OnClickListener() {
                @Override public void onClick(View v) { captureHome(false); }
            });
            mark.setTextSize(20); enable(mark, ready && !busy && !edits.hasWork());
            if (home) {
                note(String.format(Locale.US, "Home: %.6f, %.6f", Double.parseDouble(values.getProperty("homeLat")), Double.parseDouble(values.getProperty("homeLon"))), false);
                Button clear = button("Clear Home", "Remove the saved Home location", new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmClearHome(); }
                });
                clear.setTextSize(20); enable(clear, ready && !busy && !edits.hasWork());
                columns(mark, 1.7f, clear, 1f);
            } else {
                TextView none = text("No Home location set.", 22, muted);
                columns(none, 1f, mark, 1.5f);
            }
        } else if (!blocked && enabled && item.options && !absent) {
            if (item.id.equals("dst")) {
                note("Daylight-saving schedule", false);
                choice("region", new String[]{"us", "eu", "none"}, new String[]{"US / Canada", "Europe", "No daylight saving"});
            } else {
                note("Apply at boot below this outdoor temperature", false);
                LinearLayout temp = new LinearLayout(this); temp.setGravity(Gravity.CENTER_VERTICAL); temp.setPadding(dp(18), 0, dp(18), 0);
                temp.addView(button("−", "Lower temperature threshold", new View.OnClickListener() { @Override public void onClick(View v) { adjustThreshold(-1); } }), new LinearLayout.LayoutParams(dp(72), dp(60)));
                TextView reading = text(thresholdLabel(), 24, ink); reading.setGravity(Gravity.CENTER); temp.addView(reading, new LinearLayout.LayoutParams(0, dp(64), 1));
                temp.addView(button("+", "Raise temperature threshold", new View.OnClickListener() { @Override public void onClick(View v) { adjustThreshold(1); } }), new LinearLayout.LayoutParams(dp(72), dp(60)));
                controls.addView(temp); divider(controls);
                choice("units", new String[]{"F", "C"}, new String[]{"Fahrenheit (°F)", "Celsius (°C)"});
                heater("driver", "Driver seat", "driverLevel"); heater("wheel", "Steering wheel", null); heater("passenger", "Passenger seat (only with a passenger)", "passengerLevel");
            }
        }
    }
    /** The middle pane for Support: this head unit's software, then the log button or where the log went. */
    private void support() {
        String product = "";
        try { product = Platform.property("ro.build.product"); } catch (Exception ignored) { }
        String version = installedVersion();
        note("Tilepad " + version + (product.isEmpty() ? "" : " on " + product), false);
        if (!checking) {
            boolean any = false;
            for (String id : Config.IDS) any |= !unavailable(id).isEmpty();
            note(any ? "Some tweaks aren't available on this head unit; their entries say why." : "Every tweak is available on this head unit.", false);
        }
        divider(controls);
        if (!supportSaved.isEmpty()) {
            note("Log saved to the USB drive as " + supportSaved + ". On a computer, open a new issue at "
                + "github.com/KramerTech/gen5-Tilepad/issues, say what happened and when, and attach that .zip file.", false);
        } else note("A tweak not taking effect, or something happening that shouldn't? Save a log while you can still see it, or as soon as you notice, before turning the car off.", false);
        if (reportNotice != null) note(reportNotice, true);
        if (savingReport) progress("Saving the log. This can take a minute; keep the USB drive plugged in.");
        Button save = button(savingReport ? "Saving Log…" : "Save Log to USB", "Save a log of what the head unit is doing to the USB drive", new View.OnClickListener() {
            @Override public void onClick(View v) { saveReport(true); }
        });
        save.setTextSize(22); enable(save, ready && !busy && !edits.hasWork());
        action(save); divider(controls);
    }
    /** The middle pane for Package Manager: what's installed, then the updates. */
    private void packageManager() {
        note("Tilepad " + installedVersion() + " is installed.", false);
        divider(controls);
        updates();
    }
    /** Package Manager's updates: how the last install went, then the check, what it found, and the install. */
    private void updates() {
        String last = values.getProperty("update.state", "");
        String[] ids = values.getProperty("update.items", "").split(",");
        if (last.equals("installing") && step == Step.NONE) {
            // Reopened while the install runs: it restarts the head unit itself.
            List<String> names = new ArrayList<>();
            for (String id : ids) names.add(values.getProperty("update." + id + ".name", id) + " " + values.getProperty("update." + id + ".version", ""));
            progress("Installing " + join(names) + ". The head unit restarts by itself when it's done; keep the car on.");
            divider(controls);
            return;
        }
        long started = 0;
        try { started = Long.parseLong(values.getProperty("update.started", "0")); } catch (NumberFormatException ignored) { }
        // How the last install went, until this session tries another.
        if (step == Step.NONE && updateNotice == null && last.equals("done")) for (String id : ids) {
            String p = "update." + id + ".", name = values.getProperty(p + "name", id), version = values.getProperty(p + "version", ""), state = values.getProperty(p + "state", "");
            boolean current = "true".equals(values.getProperty(p + "current"));
            boolean fresh = values.getProperty(p + "from", "").isEmpty();
            if (state.equals("installed") && current && System.currentTimeMillis() - started < 3 * 86400000L)
                note(fresh ? "Installed " + name + " " + version + "." : "Updated " + name + " to " + version + ".", false);
            else if (!current && (state.equals("failed") || state.equals("interrupted"))) {
                // NEMESIS removes the old version first, so a failed update can leave none.
                boolean gone = !fresh && values.getProperty(p + "installed", "x").isEmpty();
                note(name + " " + version + (state.equals("failed") ? " didn't install: " + values.getProperty(p + "detail", "")
                    : " didn't finish installing, maybe because the car turned off.")
                    + (gone ? " NEMESIS had already removed " + name + " " + values.getProperty(p + "from", "") + ", so it isn't installed now. Install it from USB."
                        : " Check again to retry, or install it from USB."), true);
            }
        }
        if (step == Step.CHECKING) progress("Checking GitHub for updates…");
        // A failed check or install says why right under the heading, where the pane opens.
        if (updateNotice != null) note(updateNotice, true);
        if (step == Step.CHECKED || step == Step.DOWNLOADING || step == Step.INSTALLING)
            for (Updater.Offer o : offers) offer(o);
        boolean available = false;
        for (Updater.Offer o : offers) available |= o.installable();
        List<Updater.Offer> picked = picked();
        if (step == Step.DOWNLOADING) {
            Updater.Offer o = installing.get(Math.min(downloading, installing.size() - 1));
            progress("Downloading " + o.name + " " + o.version + (installing.size() > 1 ? " (" + (downloading + 1) + " of " + installing.size() + ")" : "")
                + (downloaded >= 0 ? "… " + downloaded + "%" : "…"));
        } else if (step == Step.INSTALLING)
            progress("Installing " + (installing.size() == 1 ? installing.get(0).name + " " + installing.get(0).version : installing.size() + " updates")
                + ". The head unit restarts by itself when it's done; keep the car on.");
        else if (step == Step.CHECKED && available)
            note("Installing takes a minute or two, then the head unit restarts: the screen, including the rearview camera, is off for a minute or two. Do it while parked, and keep the car on until it's back.", false);
        Button b;
        if (step == Step.CHECKED && available) {
            String label = picked.size() == 1 ? "Install " + picked.get(0).name + " " + picked.get(0).version + " and Restart"
                : picked.isEmpty() ? "Install and Restart" : "Install " + picked.size() + " Updates and Restart";
            b = button(label, "Download and install the ticked updates, then restart the head unit", new View.OnClickListener() {
                @Override public void onClick(View v) { confirmInstall(); }
            });
            b.setTextSize(22); enable(b, ready && !busy && !edits.hasWork() && !picked.isEmpty());
        } else if (step == Step.DOWNLOADING || step == Step.INSTALLING) {
            b = button("Installing…", "Installing updates", null); b.setTextSize(22); enable(b, false);
        } else {
            b = button(step == Step.CHECKING ? "Checking…" : step == Step.CHECKED ? "Check Again" : "Check for Updates", "Ask GitHub for newer releases, over Wi-Fi", new View.OnClickListener() {
                @Override public void onClick(View v) { checkForUpdates(); }
            });
            b.setTextSize(22); enable(b, ready && !busy && !edits.hasWork());
        }
        action(b); divider(controls);
        int linked = sources().size();
        note((linked == 0 ? "Other NEMESIS packages can install and update here too, straight from their GitHub releases, even ones that aren't in the NEMESIS Package Store."
            : "Also checks " + linked + (linked == 1 ? " other package" : " other packages") + " linked to GitHub.")
            + " Packages installed from NEMESIS Manager and not linked here stay NEMESIS Manager's to update.", false);
        Button configure = button("Configure Other Software", "Link NEMESIS packages to the GitHub repositories they come from, or install one from there", new View.OnClickListener() {
            @Override public void onClick(View v) { configureSources(); }
        });
        configure.setTextSize(22); enable(configure, ready && !busy && !edits.hasWork() && !preview);
        action(configure); divider(controls);
    }
    /** One package in the check's list: a checkbox when it can be installed, what's installed, and its release notes. */
    private void offer(final Updater.Offer o) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(84)); row.setPadding(dp(30), dp(8), dp(12), dp(8));
        final boolean can = o.installable(), allowed = can && step == Step.CHECKED && ready && !busy;
        boolean ticked = can && chosen.contains(o.id);
        if (can) {
            row.setBackground(drawable("bt_list_selector_minimal", selector(false)));
            CheckBox check = new CheckBox(this); check.setChecked(ticked); check.setClickable(false); check.setFocusable(false);
            Drawable stockCheck = drawable("eco30_common_checkbox", null); if (stockCheck != null) check.setButtonDrawable(stockCheck);
            check.setDuplicateParentStateEnabled(true); row.addView(check, new LinearLayout.LayoutParams(dp(40), dp(40)));
            row.setEnabled(allowed); row.setAlpha(allowed || step != Step.CHECKED ? 1f : .5f); row.setFocusable(allowed);
            row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                if (!chosen.remove(o.id)) chosen.add(o.id);
                render();
            } });
        } else row.addView(new View(this), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout words = column(); words.setPadding(dp(10), 0, dp(8), 0);
        TextView title = text(o.name + (o.newer() ? " " + o.version : ""), 24, ink); words.addView(title);
        String detail; boolean problem = false;
        if (o.problem != null) { detail = "Couldn't check: " + o.problem; problem = true; }
        else if (o.fresh() && can) detail = "Not installed here · " + o.repo + ". Tick to install it.";
        else if (o.fresh()) { detail = "Not installed here, and this release can't be installed from here. Install it from USB, or remove its link under Configure Other Software."; problem = true; }
        else if (can) detail = "Installed: " + o.installed + (o.id.equals(SelfUpdate.TILEPAD) ? "" : " · " + o.repo);
        else if (o.newer()) { detail = "Installed: " + o.installed + ". This release can't be installed from here; install it from USB (see its page on GitHub)."; problem = true; }
        else if (SelfUpdate.version(o.installed) == null) detail = "Latest release: " + o.version + ". The installed version can't be read.";
        else if (SelfUpdate.compare(o.version, o.installed) < 0) detail = o.installed + " is newer than GitHub's latest release, " + o.version + ".";
        else detail = o.installed + " is the latest version.";
        TextView sub = text(detail, 18, problem ? 0xffb54b30 : muted); sub.setPadding(0, dp(2), 0, 0); words.addView(sub);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription(o.name + ", " + detail + (can ? ticked ? ", ticked" : ", not ticked" : ""));
        if (o.newer()) {
            Button info = button("ⓘ", "Release notes for " + o.name + " " + o.version, new View.OnClickListener() {
                @Override public void onClick(View v) { releaseNotes(o); }
            });
            info.setTextSize(28); enable(info, true);
            row.addView(info, new LinearLayout.LayoutParams(dp(64), dp(56)));
        }
        controls.addView(row); divider(controls);
    }
    private List<Updater.Offer> picked() {
        List<Updater.Offer> out = new ArrayList<>();
        for (Updater.Offer o : offers) if (o.installable() && chosen.contains(o.id)) out.add(o);
        return out;
    }
    private static String join(List<String> names) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < names.size(); i++) s.append(i == 0 ? "" : i == names.size() - 1 ? " and " : ", ").append(names.get(i));
        return s.toString();
    }
    /** A release's notes, in a scrollable window. */
    private void releaseNotes(Updater.Offer o) {
        ScrollView scroll = new ScrollView(this); scroll.setScrollbarFadingEnabled(false);
        TextView body = text((o.repo + " · release " + o.tag) + "\n\n" + (o.notes.isEmpty() ? "This release has no notes." : o.notes), 20, ink);
        body.setGravity(Gravity.TOP); body.setLineSpacing(dp(3), 1f); body.setPadding(dp(28), dp(20), dp(28), dp(20)); body.setTextIsSelectable(false);
        scroll.addView(body);
        AlertDialog d = dialog(o.name + " " + o.version, scroll).setPositiveButton("Close", null).create();
        d.show();
        // Long notes scroll inside a window that leaves the screen's edges clear.
        d.getWindow().setLayout(Math.min(dp(820), getResources().getDisplayMetrics().widthPixels - dp(80)), Math.min(dp(560), getResources().getDisplayMetrics().heightPixels - dp(60)));
    }
    /** Wi-Fi off or not connected, or the clock not set: a window that says so, and opens Wi-Fi settings when they can fix it. */
    private void networkProblem(Updater.NetworkProblem p) {
        AlertDialog.Builder b = dialog(p.settings ? "Wi-Fi Needed" : "Can't Check for Updates Yet", words(p.getMessage())).setNegativeButton("Close", null);
        if (p.settings) b.setPositiveButton("Wi-Fi Settings", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                try { startActivity(new android.content.Intent("android.settings.WIFI_SETTINGS")); }
                catch (android.content.ActivityNotFoundException e) { Toast.makeText(TweaksActivity.this, "This head unit has no Wi-Fi settings screen.", Toast.LENGTH_LONG).show(); }
            }
        });
        b.show();
    }
    private boolean updating() { return step == Step.CHECKING || step == Step.DOWNLOADING || step == Step.INSTALLING; }
    private String installedVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }
    /** source.<id> = owner/repo from the settings: the packages linked to GitHub. */
    private Properties sources() {
        Properties out = new Properties();
        for (String key : values.stringPropertyNames()) if (key.startsWith("source.")) out.setProperty(key.substring(7), values.getProperty(key));
        return out;
    }
    private void checkForUpdates() {
        if (!ready || busy || edits.hasWork()) return;
        final String current = installedVersion(); final Properties sources = sources();
        final Step before = step;
        busy = true; step = Step.CHECKING; updateNotice = null; render();
        worker.execute(new Runnable() { @Override public void run() {
            List<Updater.Offer> found = null; String notice = null;
            Updater.NetworkProblem offline = Updater.networkProblem(TweaksActivity.this);
            if (offline == null) {
                try { found = Updater.check(TweaksActivity.this, current, sources); }
                catch (Updater.NetworkProblem e) { offline = e; }
                catch (Exception e) { notice = failure("check", e); }
            }
            final List<Updater.Offer> list = found; final String problem = notice; final Updater.NetworkProblem wifi = offline;
            main.post(new Runnable() { @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                busy = false; updateNotice = problem;
                if (list != null) {
                    offers = list; chosen.clear(); step = Step.CHECKED;
                    // New installs wait to be ticked; updates start ticked.
                    for (Updater.Offer o : list) if (o.installable() && !o.fresh()) chosen.add(o.id);
                } else step = before == Step.CHECKED ? Step.CHECKED : Step.NONE;
                controlScroll.scrollTo(0, 0); render();
                if (wifi != null) networkProblem(wifi);
            } });
        } });
    }
    private void confirmInstall() {
        final List<Updater.Offer> picked = picked();
        if (picked.isEmpty()) return;
        StringBuilder list = new StringBuilder();
        for (Updater.Offer o : picked)
            list.append("• ").append(o.name).append(' ').append(o.fresh() ? o.version + " (new)" : o.installed + " → " + o.version).append('\n');
        dialog(picked.size() == 1 ? "Install " + picked.get(0).name + " " + picked.get(0).version + "?" : "Install " + picked.size() + " Updates?",
                words(list + "\nThe head unit restarts when the installs finish, a minute or two from now. Keep the car parked and on until it's back."))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install and Restart", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) { install(picked); }
            }).show();
    }
    /** Downloads the releases, then hands them to the root command, which checks them and installs them without this app. */
    private void install(final List<Updater.Offer> picked) {
        if (!ready || busy || edits.hasWork() || preview) return;
        final String current = installedVersion();
        busy = true; step = Step.DOWNLOADING; installing = picked; downloading = 0; downloaded = -1; updateNotice = null; render();
        worker.execute(new Runnable() { @Override public void run() {
            String problem = null; Updater.NetworkProblem offline = null;
            try {
                List<String> args = Updater.download(TweaksActivity.this, picked, current, new Updater.Progress() {
                    @Override public void at(final int index, final int percent) {
                        if (percent >= 0 && percent % 5 != 0) return;
                        main.post(new Runnable() { @Override public void run() { if (step == Step.DOWNLOADING) { downloading = index; downloaded = percent; render(); } } });
                    }
                });
                main.post(new Runnable() { @Override public void run() { if (isFinishing() || isDestroyed()) return; step = Step.INSTALLING; controlScroll.scrollTo(0, 0); render(); } });
                args.add(0, "self-update");
                Backend.run(args.toArray(new String[0]));
            } catch (Updater.NetworkProblem e) { offline = e; problem = "Nothing was installed: Wi-Fi went away while downloading. Check again to retry."; }
            catch (Exception e) { problem = failure("install", e); }
            finally { Updater.clear(TweaksActivity.this); }
            if (problem != null) {
                final String notice = problem; final Updater.NetworkProblem wifi = offline;
                main.post(new Runnable() { @Override public void run() {
                    if (isFinishing() || isDestroyed()) return;
                    // Retrying starts with a fresh check: GitHub's addresses and checksums may have changed.
                    busy = false; step = Step.NONE; offers = new ArrayList<>(); updateNotice = notice; controlScroll.scrollTo(0, 0); render();
                    if (wifi != null) networkProblem(wifi);
                } });
                return;
            }
            // The installs restart the head unit when any of them worked. Until then, watch for all of them failing.
            for (int i = 0; i < 360; i++) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                Properties read;
                try { read = Backend.run("settings"); } catch (Exception busyInstalling) { continue; }
                if (!"done".equals(read.getProperty("update.state"))) continue;
                boolean restarting = false;
                for (String id : read.getProperty("update.items", "").split(",")) restarting |= "installed".equals(read.getProperty("update." + id + ".state"));
                if (restarting) continue;
                final Properties loaded = read;
                main.post(new Runnable() { @Override public void run() {
                    if (isFinishing() || isDestroyed()) return;
                    edits.reset(loaded); busy = false; step = Step.NONE; offers = new ArrayList<>(); controlScroll.scrollTo(0, 0); render();
                } });
                return;
            }
        } });
    }
    /** A check or install failure, in words: the network's own exceptions say little on their own. */
    private static String failure(String action, Exception e) {
        String what = action.equals("check") ? "Couldn't check for updates. " : "Nothing was installed. ";
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException || e instanceof java.net.NoRouteToHostException)
            return what + "GitHub can't be reached. Check that this Wi-Fi network has internet access.";
        if (e instanceof java.net.SocketTimeoutException) return what + "GitHub didn't answer in time. Try again.";
        if (e instanceof javax.net.ssl.SSLException) return what + "The secure connection to GitHub failed (" + e.getMessage() + ").";
        if (e instanceof org.json.JSONException) return what + "GitHub's answer couldn't be read.";
        return what + (e.getMessage() == null ? e.toString() : e.getMessage());
    }
    /**
     * Links installed NEMESIS packages to their GitHub repositories, in two steps: the GitHub user or
     * organization, then one of its repositories, picked from a list a filter narrows. The packages in
     * that repository's latest release that are installed here get linked. Lists and unlinks the rest.
     */
    private void configureSources() {
        if (!ready || busy || edits.hasWork()) return;
        LinearLayout box = column(); box.setPadding(dp(28), dp(6), dp(28), dp(8));
        final LinearLayout stage = column(); box.addView(stage);
        final LinearLayout result = column(); box.addView(result);
        final LinearLayout linked = column(); box.addView(linked);
        ScrollView scroll = new ScrollView(this); scroll.setScrollbarFadingEnabled(false); scroll.addView(box);
        final AlertDialog dialog = sourcesDialog = dialog("Other Software", scroll).setPositiveButton("Close", null).create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() { @Override public void onDismiss(DialogInterface d) {
            // What the last check found may not match the links any more.
            if (step == Step.CHECKED) { step = Step.NONE; offers = new ArrayList<>(); }
            if (sourcesDialog == dialog) sourcesDialog = null;
            render();
        } });
        final Runnable showLinks = new Runnable() { @Override public void run() { links(linked, result); } };
        askOwner(stage, result, showLinks, "");
        showLinks.run();
        dialog.show();
        dialog.getWindow().setLayout(Math.min(dp(900), getResources().getDisplayMetrics().widthPixels - dp(60)), -2);
    }
    /** Step one: the GitHub user or organization. A pasted owner/repo or github.com address skips step two. */
    private void askOwner(final LinearLayout stage, final LinearLayout result, final Runnable showLinks, String typed) {
        stage.removeAllViews();
        TextView intro = text("Install and update packages straight from their developers' GitHub releases, even ones not in the NEMESIS "
            + "Package Store. Only packages linked here are managed here; others stay with NEMESIS Manager. Type the GitHub user "
            + "or organization that publishes the package.", 19, muted);
        intro.setGravity(Gravity.TOP); stage.addView(intro);
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(8), 0, dp(2));
        final EditText owner = field("GitHub user, e.g. KramerTech"); owner.setText(typed); owner.setSelection(typed.length());
        row.addView(owner, new LinearLayout.LayoutParams(0, dp(64), 1));
        final Button next = button("Next", "Find this user's repositories", null); next.setTextSize(22); enable(next, true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(140), dp(56)); np.leftMargin = dp(12);
        row.addView(next, np);
        stage.addView(row);
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            String t = owner.getText().toString().trim();
            String[] named = Repos.pasted(t);
            String login = t.replaceFirst("^(https?://)?(www\\.)?github\\.com/", "").replaceFirst("^@", "").replaceAll("/+$", "");
            if (named == null && !SelfUpdate.validRepo(login + "/x")) { message(result, "Type a GitHub user or organization, e.g. KramerTech.", true); return; }
            hideKeyboard(owner);
            if (named != null) findSource(named[0], named[1], new View[]{owner, next}, result, showLinks);
            else loadAccount(login, new View[]{owner, next}, stage, result, showLinks);
        } });
        owner.setImeOptions(owner.getImeOptions() | android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        owner.setOnEditorActionListener(new TextView.OnEditorActionListener() { @Override public boolean onEditorAction(TextView v, int action, KeyEvent e) {
            next.performClick(); return true;
        } });
    }
    private void loadAccount(final String owner, final View[] lock, final LinearLayout stage, final LinearLayout result, final Runnable showLinks) {
        lock(lock, true);
        working(result, "Finding " + owner + "'s repositories…");
        final String current = installedVersion();
        worker.execute(new Runnable() { @Override public void run() {
            Updater.NetworkProblem offline = Updater.networkProblem(TweaksActivity.this);
            Updater.Account found = null; String failed = null;
            if (offline == null) try { found = Updater.account(TweaksActivity.this, current, owner); }
            catch (Updater.NetworkProblem e) { offline = e; }
            catch (Exception e) { failed = failure("check", e).replace("Couldn't check for updates. ", ""); }
            final Updater.Account account = found; final String problem = failed; final Updater.NetworkProblem wifi = offline;
            main.post(new Runnable() { @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                lock(lock, false);
                result.removeAllViews();
                if (wifi != null) networkProblem(wifi);
                else if (problem != null) message(result, problem, true);
                else pickRepository(account, stage, result, showLinks);
            } });
        } });
    }
    /** Step two: the account's repositories, NEMESIS ones first, narrowed by what's typed; tapping one finds its packages. */
    private void pickRepository(final Updater.Account account, final LinearLayout stage, final LinearLayout result, final Runnable showLinks) {
        stage.removeAllViews();
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView who = text(account.login, 24, ink); who.setTypeface(null, Typeface.BOLD);
        top.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
        final Button change = button("Change User", "Type a different GitHub user", new View.OnClickListener() {
            @Override public void onClick(View v) { result.removeAllViews(); askOwner(stage, result, showLinks, account.login); }
        });
        change.setTextSize(20); enable(change, true);
        change.setPadding(dp(24), 0, dp(24), 0);
        top.addView(change, new LinearLayout.LayoutParams(-2, dp(56)));
        stage.addView(top);
        final EditText filter = field("Search " + account.login + "'s repositories");
        stage.addView(filter, new LinearLayout.LayoutParams(-1, dp(64)));
        final TextView hint = text("", 19, muted); hint.setGravity(Gravity.TOP); hint.setPadding(0, dp(8), 0, dp(4)); stage.addView(hint);
        final LinearLayout list = column(); stage.addView(list);
        final TextView more = text("", 19, muted); more.setPadding(0, dp(6), 0, 0); stage.addView(more);
        final Runnable show = new Runnable() { @Override public void run() {
            String typed = filter.getText().toString().trim();
            List<Repos.Listed> shown = Repos.matching(account.repos, typed);
            int nemesis = 0;
            for (Repos.Listed l : account.repos) if (l.nemesis) nemesis++;
            hint.setText(account.repos.isEmpty() ? account.login + " has no public repositories."
                : !typed.isEmpty() ? (shown.isEmpty() ? "No repository name contains “" + typed + "”." : "")
                : nemesis > 0 ? (nemesis == 1 ? "1 repository mentions" : nemesis + " repositories mention") + " NEMESIS"
                    + (account.repos.size() > nemesis ? ". Search to see all " + account.repos.size() + "." : ".")
                : "None of " + account.login + "'s repositories mention NEMESIS. Search to narrow them down.");
            hint.setVisibility(hint.getText().length() == 0 ? View.GONE : View.VISIBLE);
            list.removeAllViews();
            final List<View> lock = new ArrayList<>(Arrays.<View>asList(filter, change));
            final List<View> rows = new ArrayList<>();
            for (int i = 0; i < Math.min(shown.size(), REPOS_SHOWN); i++) {
                final Repos.Listed l = shown.get(i);
                // The whole row picks the repository.
                LinearLayout row = column(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(64)); row.setPadding(dp(8), dp(6), dp(8), dp(6));
                row.addView(text(l.name, 22, ink));
                if (!l.description.isEmpty()) { TextView d = text(l.description, 17, muted); d.setMaxLines(2); d.setEllipsize(android.text.TextUtils.TruncateAt.END); row.addView(d); }
                row.setBackground(drawable("bt_list_selector", selector(false))); row.setClickable(true); row.setFocusable(true);
                row.setContentDescription(l.name + (l.description.isEmpty() ? "" : ", " + l.description.replaceFirst("\\.$", "")) + ". Links the packages in its latest release.");
                rows.add(row); lock.add(row);
                list.addView(row);
                View rule = new View(TweaksActivity.this); rule.setBackgroundColor(line); list.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
            }
            for (int i = 0; i < rows.size(); i++) {
                final Repos.Listed l = shown.get(i);
                rows.get(i).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    hideKeyboard(filter);
                    findSource(account.login, l.name, lock.toArray(new View[0]), result, showLinks);
                } });
            }
            int rest = shown.size() - REPOS_SHOWN;
            more.setText(rest > 0 ? "And " + rest + " more: type more of the name." : "");
            more.setVisibility(rest > 0 ? View.VISIBLE : View.GONE);
        } };
        filter.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) { show.run(); }
        });
        show.run();
    }
    private static final int REPOS_SHOWN = 6;
    /** Disables views while a lookup runs; disabled ones fade like buttons. */
    private static void lock(View[] views, boolean locked) { for (View v : views) { v.setEnabled(!locked); v.setAlpha(locked ? .4f : 1f); } }
    private void hideKeyboard(View from) {
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(from.getWindowToken(), 0);
    }
    /** A spinner and what's happening, in place of the last result. */
    private void working(LinearLayout result, String label) {
        result.removeAllViews();
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(10), 0, dp(10));
        ProgressBar spinner = new ProgressBar(this); spinner.setIndeterminate(true); row.addView(spinner, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView doing = text(label, 19, ink); doing.setPadding(dp(14), 0, 0, 0); row.addView(doing);
        result.addView(row);
    }
    private EditText field(String hint) {
        EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true); e.setTextSize(22); e.setTextColor(ink); e.setHintTextColor(line == 0xff41494e ? 0xff7d878d : 0xff9a9a9a);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        return e;
    }
    private void message(LinearLayout into, String text, boolean problem) {
        into.removeAllViews();
        TextView v = text(text, 19, problem ? 0xffb54b30 : ink); v.setGravity(Gravity.TOP); v.setPadding(0, dp(10), 0, dp(10)); into.addView(v);
    }
    /** Looks up the repository's latest release, links what's installed here, and says what it found. */
    private void findSource(final String owner, final String name, final View[] lock, final LinearLayout result, final Runnable showLinks) {
        lock(lock, true);
        working(result, "Reading " + owner + "/" + name + "'s latest release…");
        final String current = installedVersion();
        worker.execute(new Runnable() { @Override public void run() {
            Updater.NetworkProblem offline = Updater.networkProblem(TweaksActivity.this);
            final StringBuilder said = new StringBuilder(); boolean problem = true; Properties read = null;
            final List<Updater.Offer> fresh = new ArrayList<>();
            if (offline == null) try {
                Updater.Repo repo = Updater.find(TweaksActivity.this, current, owner, name);
                Map<String, SelfUpdate.Package> installed = Updater.installed();
                if (repo.packages.isEmpty())
                    said.append("The latest release of ").append(repo.name).append(" has no NEMESIS package Tilepad can read.");
                for (Updater.Offer o : repo.packages) {
                    if (said.length() > 0) said.append("\n\n");
                    SelfUpdate.Package p = installed.get(o.id);
                    if (o.id.equals(SelfUpdate.TILEPAD)) said.append("That's Tilepad's own repository: Tilepad already checks it.");
                    else if ((o.installed == null || p == null) && o.installable()) {
                        // Linked when it's installed, so the check doesn't list what the user only looked at.
                        o.installed = null;
                        said.append(o.name).append(' ').append(o.version).append(" isn't installed here. Install it from ").append(repo.name)
                            .append(", and Package Manager keeps it up to date.");
                        fresh.add(o); problem = false;
                    }
                    else if (o.installed == null || p == null) said.append(o.name).append(" (").append(o.id).append(") is in this release but isn't installed here, and this release can't be installed from here. Install it from USB.");
                    else if (p.locked) said.append(o.name).append(" is part of NEMESIS and updates with it.");
                    else {
                        read = Backend.run("update-source", "add", o.id, repo.name);
                        said.append("Linked ").append(o.name).append(" (installed ").append(o.installed).append(") to ").append(repo.name)
                            .append(". Its latest release is ").append(o.version).append('.');
                        problem = false;
                    }
                }
            } catch (Updater.NetworkProblem e) { offline = e; }
            catch (Exception e) { said.append(failure("check", e).replace("Couldn't check for updates. ", "")); }
            final Properties loaded = read; final boolean bad = problem; final Updater.NetworkProblem wifi = offline;
            main.post(new Runnable() { @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (loaded != null) edits.reset(loaded);
                lock(lock, false);
                if (wifi != null) { result.removeAllViews(); networkProblem(wifi); }
                else message(result, said.toString(), bad);
                if (wifi == null) for (final Updater.Offer o : fresh) {
                    Button add = button("Install " + o.name + " " + o.version, "Install " + o.name + " from GitHub, then restart the head unit", new View.OnClickListener() {
                        @Override public void onClick(View v) { confirmFresh(o); }
                    });
                    add.setTextSize(22); enable(add, ready && !busy && !edits.hasWork());
                    LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(60)); ap.setMargins(0, dp(4), 0, dp(8));
                    result.addView(add, ap);
                }
                showLinks.run();
            } });
        } });
    }
    /** Installs a package that isn't on this head unit yet: links it, closes the dialog, and installs it like an update. */
    private void confirmFresh(final Updater.Offer o) {
        dialog("Install " + o.name + " " + o.version + "?", words(o.name + " installs from " + o.repo + "'s latest release on GitHub, with root access: "
                + "only install software you trust. Package Manager then checks it for updates with Tilepad.\n\n"
                + "The head unit restarts when the install finishes, a minute or two from now. Keep the car parked and on until it's back."))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install and Restart", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    worker.execute(new Runnable() { @Override public void run() {
                        Properties read = null; String failed = null;
                        try { read = Backend.run("update-source", "add", o.id, o.repo); } catch (Exception e) { failed = e.getMessage(); }
                        final Properties loaded = read; final String problem = failed;
                        main.post(new Runnable() { @Override public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            if (loaded == null) { dialog("Couldn't Install " + o.name, words(problem)).setPositiveButton("Close", null).show(); return; }
                            edits.reset(loaded);
                            if (sourcesDialog != null) sourcesDialog.dismiss();
                            install(Collections.singletonList(o));
                        } });
                    } });
                }
            }).show();
    }
    /** The linked packages, each with Remove. */
    private void links(final LinearLayout into, final LinearLayout result) {
        into.removeAllViews();
        values = edits.display();
        Properties sources = sources();
        TextView heading = text(sources.isEmpty() ? "No packages linked yet." : "Linked", 22, ink); heading.setPadding(0, dp(10), 0, dp(4));
        if (!sources.isEmpty()) heading.setTypeface(null, Typeface.BOLD);
        into.addView(heading);
        Map<String, SelfUpdate.Package> installed = Updater.installed();
        for (final String id : new TreeSet<>(sources.stringPropertyNames())) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(60));
            SelfUpdate.Package p = installed.get(id);
            final String name = p == null ? id : p.name;
            TextView label = text(name + "\n" + sources.getProperty(id) + (p == null ? " · not installed" : ""), 19, ink); label.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            final Button remove = button("Remove", "Stop checking GitHub for " + name, null); remove.setTextSize(20); enable(remove, true);
            remove.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                enable(remove, false);
                worker.execute(new Runnable() { @Override public void run() {
                    Properties read = null; String failed = null;
                    try { read = Backend.run("update-source", "remove", id); } catch (Exception e) { failed = e.getMessage(); }
                    final Properties loaded = read; final String problem = failed;
                    main.post(new Runnable() { @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (loaded != null) { edits.reset(loaded); message(result, "Unlinked " + name + ".", false); }
                        else message(result, "Couldn't unlink " + name + ": " + problem, true);
                        links(into, result);
                    } });
                } });
            } });
            row.addView(remove, new LinearLayout.LayoutParams(dp(150), dp(52)));
            into.addView(row);
            View rule = new View(this); rule.setBackgroundColor(line); into.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }
    private void checkbox(LinearLayout parent, String label, final String key, boolean checked, boolean allowed) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(72)); row.setPadding(dp(30), dp(8), dp(18), dp(8));
        row.setBackground(drawable("bt_list_selector_minimal", selector(false)));
        CheckBox check = new CheckBox(this); check.setChecked(checked); check.setClickable(false); check.setFocusable(false);
        Drawable stockCheck = drawable("eco30_common_checkbox", null); if (stockCheck != null) check.setButtonDrawable(stockCheck);
        check.setDuplicateParentStateEnabled(true); row.addView(check, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text(label, 26, ink); title.setPadding(dp(8), 0, 0, 0); row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.setEnabled(allowed); row.setAlpha(allowed ? 1f : .5f); row.setFocusable(true);
        row.setContentDescription(label + ", " + (checked ? "on" : "off"));
        final boolean next = !checked;
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            // Disabling the pop-over also hides the nav handle (the backend enforces the same).
            if (key.equals("popover") && next) save("set", "popover", "true", "nav", "true");
            else save("set", key, Boolean.toString(next));
        } });
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); divider(parent);
    }
    private void heater(String key, String label, String level) {
        // A heater this trim doesn't have: nothing to choose, and nothing a log could add.
        if ("false".equals(audit.getProperty("fitted." + key))) {
            note("This car doesn't have a heated " + (key.equals("wheel") ? "steering wheel" : key + " seat") + ".", false); divider(controls);
            return;
        }
        Config config = new Config(values); checkbox(controls, label, key, config.checked(key), ready && !busy);
        if (key.equals("passenger")) note(TweakInfo.PASSENGER_HEAT, false);
        if (level != null && config.checked(key)) choice(level, new String[]{"1", "2", "3"}, new String[]{"1 Low", "2 Medium", "3 High"});
    }
    private void choice(final String key, final String[] keys, String[] labels) {
        RadioGroup group = new RadioGroup(this); group.setOrientation(LinearLayout.VERTICAL);
        if (!key.equals("region")) group.setOrientation(LinearLayout.HORIZONTAL);
        group.setPadding(dp(26), 0, dp(12), 0);
        Config config = new Config(values);
        for (int i = 0; i < keys.length; i++) {
            RadioButton b = new RadioButton(this); b.setId(100 + i); b.setText(labels[i]); b.setTextColor(ink); b.setTextSize(22); b.setMinHeight(dp(key.equals("region") ? 54 : 64)); b.setEnabled(ready && !busy);
            group.addView(b, new RadioGroup.LayoutParams(key.equals("region") ? -1 : 0, -2, key.equals("region") ? 0 : 1));
            if (config.get(key).equals(keys[i])) b.setChecked(true);
        }
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() { @Override public void onCheckedChanged(RadioGroup g, int id) { save("set", key, keys[id - 100]); } });
        controls.addView(group);
        if (!key.equals("region")) divider(controls);
    }
    private boolean pending(String id) { return "true".equals(values.getProperty("pending." + id)); }
    /** Firmware gates end by asking for a log; other failures (a write, a missing service) don't. */
    private static boolean compatibility(String message) { return message.contains("help add compatibility"); }
    /** Each tweak's latest status as the runtime last wrote it, re-read every two seconds while shown. */
    private final Properties live = new Properties();
    private final Runnable liveRefresh = new Runnable() { @Override public void run() {
        boolean changed = false;
        for (String id : Config.IDS) {
            String now;
            try { now = new String(com.tilepad.tweakcore.Files.read(new java.io.File(Platform.LIVE, id)), "UTF-8"); }
            catch (Exception e) { continue; }
            if (!now.equals(live.getProperty(id))) { live.setProperty(id, now); changed = true; }
        }
        if (changed && ready) render();
        main.postDelayed(this, 2000);
    } };
    private String liveStatus(String id) { return live.getProperty(id, values.getProperty("status." + id, "")); }
    /** A running tweak's own report, which can change at any moment: a pulsing green dot leads it. */
    private void live(String label) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.TOP); row.setPadding(dp(30), dp(16), dp(24), dp(16));
        View dot = new View(this);
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(0xff34d27b);
        dot.setBackground(circle);
        android.view.animation.AlphaAnimation pulse = new android.view.animation.AlphaAnimation(1f, .25f);
        pulse.setDuration(900); pulse.setRepeatMode(android.view.animation.Animation.REVERSE); pulse.setRepeatCount(android.view.animation.Animation.INFINITE);
        dot.startAnimation(pulse);
        // Centred on the first line of 22sp text.
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(dp(12), dp(12)); at.topMargin = dp(11);
        row.addView(dot, at);
        TextView text = text(label, 22, muted); text.setPadding(dp(14), 0, 0, 0);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(row);
    }
    private void progress(String label) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(30), dp(16), dp(24), dp(16));
        ProgressBar spinner = new ProgressBar(this); spinner.setIndeterminate(true);
        row.addView(spinner, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView text = text(label, 22, ink); text.setPadding(dp(18), 0, 0, 0);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(row);
    }
    private void note(String label, boolean problem) {
        TextView v = text(label, problem ? 20 : 22, problem ? 0xffb54b30 : muted); int spacing = TweakInfo.RAIL[selected].id.equals("dst") && !problem ? 7 : 16;
        v.setPadding(dp(30), dp(spacing), dp(24), dp(spacing)); controls.addView(v);
    }
    private String thresholdLabel() {
        Config config = new Config(values); double c = config.number("threshold") / 10.0, f = c * 9 / 5 + 32;
        return config.get("units").equals("F") ? String.format(Locale.US, "%.0f°F  (%.1f°C)", f, c) : String.format(Locale.US, "%.1f°C  (%.0f°F)", c, f);
    }
    private void adjustThreshold(int delta) {
        Config config = new Config(values); double c = config.number("threshold") / 10.0;
        int next = config.get("units").equals("C") ? config.number("threshold") + delta * 10 : (int) Math.round(((Math.round(c * 9 / 5 + 32) + delta) - 32) * 50.0 / 9);
        save("set", "threshold", Integer.toString(Math.max(-400, Math.min(600, next))));
    }
    private void refresh() {
        if (preview) { busy = false; ready = true; checking = false; render(); return; }
        busy = true;
        worker.execute(new Runnable() { @Override public void run() {
            Properties result = null; String failure = null;
            try { result = Backend.run("settings"); } catch (Exception e) { failure = e.getMessage(); }
            final Properties loaded = result; final String problem = failure;
            main.post(new Runnable() { @Override public void run() { if (isFinishing()) return; if (loaded != null) edits.reset(loaded); error = problem; ready = loaded != null; busy = false; render(); } });
            // Settings skips the firmware checks to open quickly; run them once afterwards so an
            // incompatible car sees why, and the report button, before trying a checkbox.
            if (result != null && !audited) {
                audited = true;
                Properties full = null;
                try { full = Backend.run("status"); } catch (Exception ignored) { }
                // A failed audit unlocks the checkboxes; the write path still checks compatibility.
                final Properties found = full;
                main.post(new Runnable() { @Override public void run() {
                    if (isFinishing()) return;
                    if (found != null) for (String key : found.stringPropertyNames())
                        if (key.startsWith("unavailable.") || key.startsWith("fitted.") || key.equals("report")) audit.setProperty(key, found.getProperty(key));
                    checking = false; render();
                } });
            }
        } });
    }
    private String unavailable(String id) {
        String reason = values.getProperty("unavailable." + id, "");
        return reason.isEmpty() ? audit.getProperty("unavailable." + id, "") : reason;
    }
    private void save(String... args) {
        if (busy || !ready) return;
        edits.edit(args[1], args[2]); error = null;
        main.removeCallbacks(saveFlush); main.postDelayed(saveFlush, 300);
        render();
    }
    private void flushEdits() {
        // The worker drains pending edits itself, including after onPause/onDestroy.
        // New taps replace pending values while one batch is running.
        worker.execute(new Runnable() { @Override public void run() {
            for (String[] args; (args = edits.take()) != null;) {
                Properties read = null; String failure = null;
                try {
                    if (preview) {
                        read = edits.confirmed(); Config config = new Config(read);
                        for (int i = 1; i < args.length; i += 2) config.set(args[i], args[i + 1]);
                        java.io.StringWriter stored = new java.io.StringWriter(); read.store(stored, "Preview");
                        getPreferences(0).edit().putString("preview", stored.toString()).apply();
                    } else read = Backend.run(args);
                } catch (Exception e) {
                    failure = e.getMessage();
                    try { read = Backend.run("settings"); } catch (Exception ignored) { }
                }
                edits.finish(read);
                final boolean known = read != null; final String problem = failure;
                main.post(new Runnable() { @Override public void run() {
                    if (isFinishing() || isDestroyed()) return;
                    if (!known) ready = false;
                    if (problem != null) error = problem;
                    render();
                } });
                if (read == null) break;
            }
        } });
    }
    /** The compatibility log (a tweak's issue) or the support log (Support), to the USB drive. */
    private void saveReport(final boolean support) {
        if (!ready || busy || edits.hasWork()) return;
        busy = true; savingReport = true; reportNotice = null; render();
        worker.execute(new Runnable() { @Override public void run() {
            String name = "", notice = null;
            try {
                name = preview ? (support ? "tilepad-log-PREVIEW.zip" : "tilepad-compat-PREVIEW.txt")
                    : Backend.run(support ? "support" : "report").getProperty("saved", "");
            } catch (Exception e) { notice = "Couldn't save the log: " + e.getMessage(); }
            final String file = name, problem = notice;
            main.post(new Runnable() { @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                busy = false; savingReport = false; reportNotice = problem;
                if (support) supportSaved = file; else reportSaved = file;
                render();
            } });
        } });
    }
    private void confirmClearHome() {
        dialog("Clear Home?", words("Charge-Away stops changing Scheduled Charging until you set Home again. Scheduled Charging is left as it is."))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear Home", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) { captureHome(true); }
            }).show();
    }
    /** Saves the current GPS fix as Home, or clears Home. */
    private void captureHome(final boolean clear) {
        if (!ready || busy || edits.hasWork()) return;
        busy = true; capturingHome = true; error = null; render();
        worker.execute(new Runnable() { @Override public void run() {
            Properties read = null; String failure = null;
            try {
                if (preview) {
                    read = edits.confirmed(); Config c = new Config(read);
                    c.set("homeLat", clear ? "" : "37.0"); c.set("homeLon", clear ? "" : "-122.0");
                    java.io.StringWriter stored = new java.io.StringWriter(); read.store(stored, "Preview");
                    getPreferences(0).edit().putString("preview", stored.toString()).apply();
                } else read = clear ? Backend.run("update", "homeLat", "", "homeLon", "") : Backend.run("home");
            } catch (Exception e) { failure = e.getMessage(); }
            final Properties result = read; final String problem = failure;
            main.post(new Runnable() { @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (result != null) edits.reset(result);
                busy = false; capturingHome = false; error = problem; render();
            } });
        } });
    }
    /**
     * A dialog in the screen's light or dark Holo theme, with its title and body drawn in the screen's
     * own colours: this firmware's dialog styles draw white text on a light window.
     */
    private AlertDialog.Builder dialog(String title, View body) {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        TextView heading = text(title, 26, ink); heading.setTypeface(null, Typeface.BOLD); heading.setGravity(Gravity.CENTER);
        heading.setPadding(dp(28), dp(18), dp(28), dp(8));
        return new AlertDialog.Builder(this, night ? AlertDialog.THEME_HOLO_DARK : AlertDialog.THEME_HOLO_LIGHT).setCustomTitle(heading).setView(body);
    }
    /** A dialog's message. */
    private View words(String message) {
        ScrollView scroll = new ScrollView(this);
        TextView v = text(message, 22, ink); v.setGravity(Gravity.TOP); v.setLineSpacing(dp(3), 1f); v.setPadding(dp(30), dp(12), dp(30), dp(22));
        scroll.addView(v);
        return scroll;
    }
    private Button button(String label, String description, View.OnClickListener click) {
        Button b = new Button(this); b.setText(label); b.setTextColor(ink); b.setTextSize(26); b.setAllCaps(false); b.setBackground(buttonBackground()); b.setContentDescription(description); enable(b, ready && !busy); b.setOnClickListener(click); return b;
    }
    /** Two columns of one row in the middle pane, each a button or a line of text, sized by weight. */
    private void columns(View left, float leftWeight, View right, float rightWeight) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(24), dp(4), dp(24), dp(8));
        View[] views = {left, right}; float[] weights = {leftWeight, rightWeight};
        for (int i = 0; i < 2; i++) {
            View v = views[i];
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, v instanceof Button ? dp(64) : -2, weights[i]);
            p.setMargins(dp(6), dp(6), dp(6), dp(6));
            if (!(v instanceof Button)) v.setPadding(0, 0, dp(8), 0);
            row.addView(v, p);
        }
        controls.addView(row);
    }
    /** A full-width action button in the middle pane, inset like the stock ones rather than filling its row. */
    private void action(Button b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(60));
        p.setMargins(dp(30), dp(10), dp(30), dp(12));
        controls.addView(b, p);
    }
    /**
     * Device Connections' raised, rounded button (co_btn_bg_bottom_*, with night versions), pressed and
     * focused for the TUNE knob. Without that app, a drawn rounded button in the theme's colours.
     */
    private Drawable buttonBackground() {
        StateListDrawable d = new StateListDrawable();
        try {
            if (buttons == null) throw new Resources.NotFoundException(BUTTONS);
            d.addState(new int[]{android.R.attr.state_pressed}, art("co_btn_bg_bottom_push"));
            d.addState(new int[]{android.R.attr.state_focused}, art("co_btn_bg_bottom_sel"));
            d.addState(new int[]{}, art("co_btn_bg_bottom_nor"));
            return d;
        } catch (Exception missing) {
            boolean night = bg == 0xff171b1f;
            d = new StateListDrawable();
            d.addState(new int[]{android.R.attr.state_pressed}, rounded(night ? 0xff101315 : 0xffdedede));
            d.addState(new int[]{android.R.attr.state_focused}, rounded(night ? 0xff2c3238 : 0xffe4f3f8));
            d.addState(new int[]{}, rounded(night ? 0xff23282d : 0xfffafafa));
            return d;
        }
    }
    private Drawable art(String name) {
        int id = buttons.getIdentifier(name, "drawable", BUTTONS);
        if (id == 0) throw new Resources.NotFoundException(name);
        return buttons.getDrawable(id);
    }
    private Drawable rounded(int fill) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(dp(12)); g.setStroke(dp(1), line);
        return g;
    }
    /** Disabled buttons fade out: the stock background doesn't change when disabled. */
    private static void enable(Button b, boolean enabled) { b.setEnabled(enabled); b.setAlpha(enabled ? 1f : .4f); }
    private StateListDrawable selector(boolean selected) {
        StateListDrawable d = new StateListDrawable(); d.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(0x3300b5de));
        d.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(0x2200b5de)); d.addState(new int[]{}, new ColorDrawable(selected ? 0x1100b5de : 0x00000000)); return d;
    }
    /** Package Manager's mark: a down arrow onto a tray, in the help mark's circle. */
    private Drawable updateMark(final int color, final int size) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();
            @Override public void draw(Canvas canvas) {
                Rect b = getBounds(); float stroke = dp(2.5f), cx = b.exactCenterX(), cy = b.exactCenterY(), r = b.width() / 2f - stroke;
                paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke);
                paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
                canvas.drawCircle(cx, cy, r, paint);
                float top = cy - r * .5f, tip = cy + r * .22f, wing = r * .3f, tray = cy + r * .5f;
                path.reset();
                path.moveTo(cx, top); path.lineTo(cx, tip);
                path.moveTo(cx - wing, tip - wing); path.lineTo(cx, tip); path.lineTo(cx + wing, tip - wing);
                path.moveTo(cx - r * .42f, tray); path.lineTo(cx + r * .42f, tray);
                canvas.drawPath(path, paint);
            }
            @Override public int getIntrinsicWidth() { return size; }
            @Override public int getIntrinsicHeight() { return size; }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
    /** A circled question mark in the label's colour, drawn so it follows the theme and selection. */
    private Drawable helpMark(final int color, final int size) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas canvas) {
                Rect b = getBounds(); float stroke = dp(2.5f);
                paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke);
                canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() / 2f - stroke, paint);
                paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(b.height() * .6f); paint.setFakeBoldText(true);
                canvas.drawText("?", b.exactCenterX(), b.exactCenterY() - (paint.descent() + paint.ascent()) / 2, paint);
            }
            @Override public int getIntrinsicWidth() { return size; }
            @Override public int getIntrinsicHeight() { return size; }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
    private void divider(LinearLayout parent) { View v = new View(this); v.setBackgroundColor(line); parent.addView(v, new LinearLayout.LayoutParams(-1, dp(1))); }
    private void verticalDivider(LinearLayout parent) { View v = new View(this); v.setBackgroundColor(line); parent.addView(v, new LinearLayout.LayoutParams(dp(1), -1)); }
}
