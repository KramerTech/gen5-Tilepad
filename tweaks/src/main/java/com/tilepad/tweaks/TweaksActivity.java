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
    /** The package being uninstalled or unlinked. */
    private String removing;
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
        TextView v = new TextView(this); v.setText(styled(label)); v.setTextColor(color); v.setTextSize(size);
        v.setGravity(Gravity.CENTER_VERTICAL); return v;
    }
    /** The car's own setting and button names are written **like this** in the copy, and drawn in bold. */
    static CharSequence styled(CharSequence text) {
        if (!(text instanceof String) || !((String) text).contains("**")) return text;
        String[] parts = ((String) text).split("\\*\\*", -1);
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        for (int i = 0; i < parts.length; i++) if (i % 2 == 1 && i < parts.length - 1) bold(out, parts[i]); else out.append(i % 2 == 1 ? "**" + parts[i] : parts[i]);
        return out;
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
        TextView title = text("Tweaks and Settings", 28, ink); title.setTypeface(null, Typeface.BOLD); title.setGravity(Gravity.CENTER);
        header.addView(title, new FrameLayout.LayoutParams(-1, -1));
        String notice = preview ? "Preview" : edits.hasWork() ? "Saving…" : busy ? (capturingHome || savingReport ? "Saving…" : step == Step.CHECKING ? "Checking…" : removing != null ? "Removing…" : updating() ? "Updating…" : "Reading settings…") : "true".equals(values.getProperty("restart")) ? "Restart required" : "";
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
            if (savingReport) progress("Capturing the log. This can take a minute, so keep the USB drive plugged in.");
            Button report = button(savingReport ? "Capturing Log…" : "Capture Compatibility Log to USB", "Save this head unit's firmware details to the USB drive", new View.OnClickListener() {
                @Override public void onClick(View v) { saveReport(false); }
            });
            report.setTextSize(22); enable(report, ready && !busy && !edits.hasWork());
            action(report);
            if (!blocked) divider(controls);
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
                TextView none = text("No Home location set.", 20, muted);
                columns(none, 1f, mark, 1.5f);
            }
        } else if (!blocked && enabled && item.options && !absent) {
            if (item.id.equals("dst")) {
                note("Daylight-saving schedule", false);
                choice("region", new String[]{"us", "eu", "none"}, new String[]{"US / Canada", "Europe", "No daylight saving"});
            } else {
                note("Turn the heaters on during startup when the outside temperature is at or below:", false);
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
            note(any ? "Some tweaks aren't compatible with this car. Select one to see why." : "Every tweak is compatible with this head unit.", false);
        }
        divider(controls);
        if (!supportSaved.isEmpty()) {
            note("Log saved to the USB drive as " + supportSaved + ". On a computer, open a new issue at "
                + "github.com/KramerTech/gen5-Tilepad/issues, say what happened and when, and attach that .zip file.", false);
        } else note("If a tweak isn't taking effect or something's not working the way it should, save a log while you can still see it, or as soon as you notice, before turning the car off.", false);
        if (reportNotice != null) note(reportNotice, true);
        if (savingReport) progress("Saving the log. This can take a minute, so keep the USB drive plugged in.");
        Button save = button(savingReport ? "Saving Log…" : "Save Log to USB", "Save a log of what the head unit is doing to the USB drive", new View.OnClickListener() {
            @Override public void onClick(View v) { saveReport(true); }
        });
        save.setTextSize(22); enable(save, ready && !busy && !edits.hasWork());
        action(save);
    }
    /**
     * The middle pane for Package Manager: Tilepad and each linked package with its version, then Check
     * for Updates beside Configure Other Software. A check that finds updates adds a checkbox column and
     * turns Check into Install; how the last install or removal went is under the buttons.
     */
    private void packageManager() {
        String last = values.getProperty("update.state", "");
        // Reopened while an install runs: it restarts the head unit itself.
        boolean installingAlready = last.equals("installing") && step == Step.NONE;
        boolean checked = !offers.isEmpty() && (step == Step.CHECKED || step == Step.DOWNLOADING || step == Step.INSTALLING);
        List<Updater.Offer> rows = checked ? offers : Updater.listed(installedVersion(), sources());
        boolean pending = false;
        for (Updater.Offer o : rows) pending |= checked && o.installable();
        boolean idle = ready && !busy && !edits.hasWork() && !installingAlready;
        note("Installed software (linked to GitHub):", false);
        divider(controls);
        for (Updater.Offer o : rows) packageRow(o, checked, pending, idle);
        List<Updater.Offer> picked = picked();
        Button left; boolean spinning = true;
        if (step == Step.CHECKING) left = button("Checking…", "Checking GitHub for updates", null);
        else if (step == Step.DOWNLOADING) {
            String percent = downloaded >= 0 ? " " + downloaded + "%" : "…";
            left = button(installing.size() > 1 ? "Downloading " + (downloading + 1) + "/" + installing.size() + percent : "Downloading" + percent, "Downloading updates", null);
        } else if (step == Step.INSTALLING || installingAlready) left = button("Installing…", "Installing updates", null);
        else {
            spinning = false;
            if (pending && picked.isEmpty()) left = button("Dismiss Updates", "Put the list back without installing anything", new View.OnClickListener() {
                @Override public void onClick(View v) { dismissUpdates(); }
            });
            else if (pending) left = button("Install " + picked.size() + (picked.size() == 1 ? " Update" : " Updates"), "Download and install the ticked updates, then restart the head unit", new View.OnClickListener() {
                @Override public void onClick(View v) { confirmInstall(); }
            });
            else left = button("Check for Updates", "Ask GitHub for newer releases, over Wi-Fi", new View.OnClickListener() {
                @Override public void onClick(View v) { checkForUpdates(); }
            });
        }
        left.setTextSize(20); enable(left, !spinning && idle);
        Button configure = button("Configure Other Software", "Link a NEMESIS package to the GitHub repository it comes from, or install one from there", new View.OnClickListener() {
            @Override public void onClick(View v) { dismissUpdates(); configureSources(); }
        });
        configure.setTextSize(20); enable(configure, idle && !preview);
        columns(spinner(left, spinning), 1f, configure, 1.25f);
        if (ready && !busy) main.post(lastInstall);
    }
    /** How the last install went, in a window, once per install, within three days of it. */
    private final Runnable lastInstall = new Runnable() { @Override public void run() {
        String started = values.getProperty("update.started", "0");
        if (isFinishing() || TweakInfo.RAIL[selected] != TweakInfo.UPDATES || step != Step.NONE || !values.getProperty("update.state", "").equals("done")
            || started.equals(getPreferences(0).getString("installShown", ""))) return;
        getPreferences(0).edit().putString("installShown", started).apply();
        long at = 0;
        try { at = Long.parseLong(started); } catch (NumberFormatException ignored) { }
        if (System.currentTimeMillis() - at > 3 * 86400000L) return;
        StringBuilder said = new StringBuilder(); boolean failed = false;
        for (String id : values.getProperty("update.items", "").split(",")) {
            String p = "update." + id + ".", name = values.getProperty(p + "name", id), version = values.getProperty(p + "version", ""), state = values.getProperty(p + "state", "");
            boolean current = "true".equals(values.getProperty(p + "current"));
            boolean fresh = values.getProperty(p + "from", "").isEmpty();
            String line = null;
            if (state.equals("installed") && current) line = fresh ? "Installed " + name + " " + version + "." : "Updated " + name + " to " + version + ".";
            else if (!current && (state.equals("failed") || state.equals("interrupted"))) {
                // NEMESIS removes the old version first, so a failed update can leave none.
                boolean gone = !fresh && values.getProperty(p + "installed", "x").isEmpty();
                line = name + " " + version + (state.equals("failed") ? " didn't install: " + values.getProperty(p + "detail", "")
                    : " didn't finish installing, maybe because the car turned off.")
                    + (gone ? " NEMESIS had already removed " + name + " " + values.getProperty(p + "from", "") + ", so it isn't installed now. Install it from USB."
                        : " Tap “Check for Updates” to try again, or install it from USB.");
                failed = true;
            }
            if (line != null) said.append(said.length() == 0 ? "" : "\n\n").append(line);
        }
        if (said.length() > 0) new Steps("").done(failed ? "Install Problem" : "Install Complete", said.toString(), failed, null);
    } };
    /** A button with a spinner inside it, at its left, while it works; the button keeps its size either way. */
    private View spinner(Button b, boolean spinning) {
        FrameLayout f = new FrameLayout(this);
        f.addView(b, new FrameLayout.LayoutParams(-1, -1));
        if (spinning) {
            ProgressBar p = new ProgressBar(this); p.setIndeterminate(true);
            FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.LEFT | Gravity.CENTER_VERTICAL); at.leftMargin = dp(16);
            f.addView(p, at);
        }
        return f;
    }
    /** Back to the plain list: what the check found goes, and nothing installs. */
    private void dismissUpdates() { step = Step.NONE; offers = new ArrayList<>(); chosen.clear(); render(); }
    /**
     * One package: a checkbox while updates are offered (a blank column for the rest), its name and
     * version, then its repository, or its update, or a problem; at the end, delete (not Tilepad), or ⓘ
     * for a release's notes while updates are offered.
     */
    private void packageRow(final Updater.Offer o, boolean checked, boolean pending, boolean idle) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(84)); row.setPadding(dp(30), dp(8), dp(12), dp(8));
        final boolean can = pending && o.installable(), allowed = can && step == Step.CHECKED && idle;
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
        } else if (pending) row.addView(new View(this), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout words = column(); words.setPadding(pending ? dp(10) : 0, 0, dp(8), 0);
        boolean known = o.installed != null && SelfUpdate.version(o.installed) != null;
        int against = checked && o.problem == null && o.version != null && known ? SelfUpdate.compare(o.version, o.installed) : 1;
        String title = o.name + (o.installed == null || o.installed.isEmpty() ? "" : " " + o.installed);
        String detail = o.installed == null ? "Not installed · " + o.repo : o.repo; boolean problem = false;
        if (checked && o.problem != null) { detail = "Couldn't check: " + o.problem; problem = true; }
        else if (can) detail = o.fresh() ? "Previous install attempt failed. Version " + o.version + " is available." : o.installed + " → " + o.version;
        else if (checked && o.newer()) {
            detail = (o.fresh() ? "Not installed on the car. " : "") + "Version " + o.version + " can't be installed from Package Manager. Install it from USB using its GitHub release.";
            problem = true;
        } else if (checked && !known) detail = "Latest release: " + o.version + ". The installed version can't be read.";
        else if (pending) detail = against == 0 ? "Up to date" : "Newer than the latest release, " + o.version;
        else if (checked) title += against == 0 ? " (latest)" : " (newer than " + o.version + ")";
        words.addView(text(title, 24, ink));
        TextView sub = text(detail, 18, problem ? 0xffb54b30 : muted); sub.setPadding(0, dp(2), 0, 0); words.addView(sub);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription(title + ", " + detail + (can ? ticked ? ", ticked" : ", not ticked" : ""));
        View end;
        if (pending && o.newer()) {
            Button info = button("ⓘ", "Release notes for " + o.name + " " + o.version, new View.OnClickListener() {
                @Override public void onClick(View v) { releaseNotes(o); }
            });
            info.setTextSize(28); enable(info, step == Step.CHECKED);
            end = info;
        } else if (o.id.equals(removing)) {
            ProgressBar p = new ProgressBar(this); p.setIndeterminate(true);
            FrameLayout f = new FrameLayout(this); f.addView(p, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
            end = f;
        } else if (!pending && !o.id.equals(SelfUpdate.TILEPAD)) {
            ImageButton delete = iconButton(trashMark(ink, dp(30)), (o.installed == null ? "Unlink " : "Uninstall ") + o.name, new View.OnClickListener() {
                @Override public void onClick(View v) { confirmRemove(o); }
            });
            boolean on = idle && step != Step.CHECKING && !preview; delete.setEnabled(on); delete.setAlpha(on ? 1f : .4f);
            end = delete;
        } else end = new View(this);
        row.addView(end, new LinearLayout.LayoutParams(dp(64), dp(56)));
        controls.addView(row); divider(controls);
    }
    /** Delete: uninstalls the package with NEMESIS, or only unlinks it so it stays installed and Package Manager stops updating it. */
    private void confirmRemove(final Updater.Offer o) {
        final Steps w = new Steps("");
        if (Updater.installed().containsKey(o.id))
            w.ask("Uninstall " + o.name + "?", "Uninstalling will use NEMESIS to remove " + o.name + (o.installed == null ? "" : " " + o.installed)
                    + " from the car, along with its apps and their data.\n\nIf you'd rather keep it installed and just stop Package Manager updating it from " + o.repo + ", unlink it instead.",
                "Uninstall", new Runnable() { @Override public void run() { remove(o, true, w); } },
                "Unlink Only", new Runnable() { @Override public void run() { remove(o, false, w); } });
        else w.ask("Unlink " + o.name + "?", o.name + " isn't installed on the car. Unlinking it stops Package Manager checking " + o.repo + " for updates.",
            "Unlink", new Runnable() { @Override public void run() { remove(o, false, w); } });
    }
    private void remove(final Updater.Offer o, final boolean uninstall, final Steps w) {
        if (!ready || busy || edits.hasWork() || preview) { w.dialog.dismiss(); return; }
        busy = true; removing = o.id; render();
        w.working((uninstall ? "Uninstalling " : "Unlinking ") + o.name + "…");
        worker.execute(new Runnable() { @Override public void run() {
            Properties read = null; String failed = null;
            try { read = uninstall ? Backend.run("uninstall", o.id) : Backend.run("update-source", "remove", o.id); } catch (Exception e) { failed = e.getMessage(); }
            final Properties loaded = read; final String problem = failed;
            main.post(new Runnable() { @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                // What a check found no longer matches what's installed.
                busy = false; removing = null; step = Step.NONE; offers = new ArrayList<>();
                if (loaded != null) {
                    edits.reset(loaded);
                    w.done(uninstall ? "Uninstalled" : "Unlinked", uninstall ? o.name + " has been uninstalled." : o.name + " has been unlinked. It stays installed, but Package Manager won't update it anymore.", false, null);
                } else w.done(uninstall ? "Couldn't Uninstall" : "Couldn't Unlink", problem, true, null);
                render();
            } });
        } });
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
    private boolean updating() { return step == Step.CHECKING || step == Step.DOWNLOADING || step == Step.INSTALLING || removing != null; }
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
        busy = true; step = Step.CHECKING; render();
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
                busy = false;
                if (problem != null) new Steps("").done("Couldn't Check for Updates", problem.replace("Couldn't check for updates. ", ""), true, null);
                if (list != null) {
                    offers = list; chosen.clear(); step = Step.CHECKED;
                    // Everything installable starts ticked, including a package whose first install failed.
                    for (Updater.Offer o : list) if (o.installable()) chosen.add(o.id);
                } else step = before == Step.CHECKED ? Step.CHECKED : Step.NONE;
                render();
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
                words(list + "\n" + INSTALL_WARNING))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install and Restart", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) { install(picked); }
            }).show();
    }
    /** Downloads the releases, then hands them to the root command, which checks them and installs them without this app. */
    private void install(final List<Updater.Offer> picked) {
        if (!ready || busy || edits.hasWork() || preview) return;
        final String current = installedVersion();
        busy = true; step = Step.DOWNLOADING; installing = picked; downloading = 0; downloaded = -1; render();
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
            } catch (Updater.NetworkProblem e) { offline = e; problem = "Wi-Fi went away while downloading. Tap “Check for Updates” to try again."; }
            catch (Exception e) { problem = failure("install", e); }
            finally { Updater.clear(TweaksActivity.this); }
            if (problem != null) {
                final String notice = problem; final Updater.NetworkProblem wifi = offline;
                main.post(new Runnable() { @Override public void run() {
                    if (isFinishing() || isDestroyed()) return;
                    // Retrying starts with a fresh check: GitHub's addresses and checksums may have changed.
                    busy = false; step = Step.NONE; offers = new ArrayList<>(); render();
                    if (wifi != null) networkProblem(wifi);
                    else new Steps("").done("Nothing Was Installed", notice.replace("Nothing was installed. ", ""), true, null);
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
     * Configure Other Software links a GitHub repository's packages, in two steps: the GitHub user or
     * organization (typed, or picked from the ones looked up before), then one of its repositories.
     * Picking one reads its latest release and says, in its own window, what linking does: link what's
     * installed here, or install what isn't. The pane's bins unlink.
     */
    private void configureSources() {
        if (!ready || busy || edits.hasWork()) return;
        LinearLayout box = column(); box.setPadding(dp(28), dp(6), dp(28), dp(8));
        final LinearLayout stage = column(); box.addView(stage);
        ScrollView scroll = new ScrollView(this); scroll.setScrollbarFadingEnabled(false); scroll.addView(box);
        final AlertDialog dialog = sourcesDialog = dialog("Link a GitHub Repo", scroll).setPositiveButton("Close", null).create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() { @Override public void onDismiss(DialogInterface d) {
            // What the last check found may not match the links any more.
            if (step == Step.CHECKED) { step = Step.NONE; offers = new ArrayList<>(); }
            if (sourcesDialog == dialog) sourcesDialog = null;
            render();
        } });
        askOwner(stage, "");
        dialog.show();
        dialog.getWindow().setLayout(Math.min(dp(900), getResources().getDisplayMetrics().widthPixels - dp(60)), -2);
    }
    private void closeSources() { if (sourcesDialog != null) sourcesDialog.dismiss(); }
    /** Step one: the GitHub user or organization, typed or picked from the ones looked up before. A pasted owner/repo or github.com address skips step two. */
    private void askOwner(final LinearLayout stage, String typed) {
        stage.removeAllViews();
        TextView intro = text("Install and update packages straight from releases on their GitHub repo. Enter the GitHub user or organization "
            + "that publishes the package to get started configuring the link.", 19, muted);
        intro.setGravity(Gravity.TOP); stage.addView(intro);
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(8), 0, dp(2));
        final EditText owner = field("GitHub user, e.g. KramerTech"); owner.setText(typed); owner.setSelection(typed.length());
        row.addView(owner, new LinearLayout.LayoutParams(0, dp(64), 1));
        final Button next = button("Next", "Find this user's repositories", null); next.setTextSize(22); enable(next, true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(140), dp(56)); np.leftMargin = dp(12);
        row.addView(next, np);
        stage.addView(row);
        // Right under the box: a spinner while it looks, or why it couldn't.
        final LinearLayout status = column(); stage.addView(status);
        final List<View> lock = new ArrayList<>(Arrays.<View>asList(owner, next));
        List<String> used = Repos.owners(values.getProperty("owners", ""));
        if (!used.isEmpty()) {
            TextView before = text("Suggested:", 19, muted); before.setPadding(0, dp(10), 0, dp(4)); stage.addView(before);
            LinearLayout list = column();
            for (final String name : used) {
                LinearLayout pick = new LinearLayout(this); pick.setGravity(Gravity.CENTER_VERTICAL); pick.setMinimumHeight(dp(60)); pick.setPadding(dp(8), dp(4), 0, dp(4));
                pick.setBackground(drawable("bt_list_selector", selector(false))); pick.setClickable(true); pick.setFocusable(true);
                pick.setContentDescription(name + ". Lists its repositories.");
                pick.addView(text(name, 22, ink), new LinearLayout.LayoutParams(0, -2, 1));
                ImageButton forget = iconButton(trashMark(ink, dp(26)), "Remove " + name + " from this list", new View.OnClickListener() {
                    @Override public void onClick(View v) { forgetOwner(name, stage, owner); }
                });
                pick.addView(forget, new LinearLayout.LayoutParams(dp(56), dp(48)));
                pick.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { owner.setText(name); next.performClick(); } });
                lock.add(pick); lock.add(forget);
                list.addView(pick);
                View rule = new View(this); rule.setBackgroundColor(line); list.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
            }
            ScrollView history = new ScrollView(this); history.setScrollbarFadingEnabled(false); history.addView(list);
            // It scrolls by itself, inside the window's own scroll.
            history.setOnTouchListener(new View.OnTouchListener() { @Override public boolean onTouch(View v, MotionEvent e) {
                v.getParent().requestDisallowInterceptTouchEvent(true); return false;
            } });
            stage.addView(history, new LinearLayout.LayoutParams(-1, used.size() > 3 ? dp(61 * 3 + 30) : -2));
        }
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            String t = owner.getText().toString().trim();
            String[] named = Repos.pasted(t);
            String login = t.replaceFirst("^(https?://)?(www\\.)?github\\.com/", "").replaceFirst("^@", "").replaceAll("/+$", "");
            if (named == null && !SelfUpdate.validRepo(login + "/x")) { message(status, "Type a GitHub user or organization, e.g. KramerTech.", true); return; }
            hideKeyboard(owner); status.removeAllViews();
            if (named != null) confirmLink(named[0], named[1]);
            else loadAccount(login, lock.toArray(new View[0]), stage, status);
        } });
        owner.setImeOptions(owner.getImeOptions() | android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        owner.setOnEditorActionListener(new TextView.OnEditorActionListener() { @Override public boolean onEditorAction(TextView v, int action, KeyEvent e) {
            next.performClick(); return true;
        } });
    }
    /** Takes a name off the looked-up list, keeping what's typed. */
    private void forgetOwner(final String name, final LinearLayout stage, final EditText owner) {
        final String list = Repos.owners(values.getProperty("owners", ""), name, true);
        worker.execute(new Runnable() { @Override public void run() {
            Properties read = null; String failed = null;
            try { read = Backend.run("update-owners", list); } catch (Exception e) { failed = e.getMessage(); }
            final Properties loaded = read; final String problem = failed;
            main.post(new Runnable() { @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (loaded == null) { new Steps("").done("Couldn't Remove " + name, problem, true, null); return; }
                edits.reset(loaded); values = edits.display();
                askOwner(stage, owner.getText().toString());
            } });
        } });
    }
    private void loadAccount(final String owner, final View[] lock, final LinearLayout stage, final LinearLayout status) {
        lock(lock, true);
        working(status, "Finding " + owner + "'s repositories…");
        final String current = installedVersion(), used = values.getProperty("owners", "");
        worker.execute(new Runnable() { @Override public void run() {
            Updater.NetworkProblem offline = Updater.networkProblem(TweaksActivity.this);
            Updater.Account found = null; String failed = null; Properties read = null;
            if (offline == null) try { found = Updater.account(TweaksActivity.this, current, owner); }
            catch (Updater.NetworkProblem e) { offline = e; }
            catch (Exception e) { failed = failure("check", e).replace("Couldn't check for updates. ", ""); }
            // Found: first on the looked-up list next time, as GitHub spells it.
            if (found != null) try { read = Backend.run("update-owners", Repos.owners(used, found.login, false)); } catch (Exception ignored) { }
            final Updater.Account account = found; final String problem = failed; final Updater.NetworkProblem wifi = offline; final Properties loaded = read;
            main.post(new Runnable() { @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                lock(lock, false);
                status.removeAllViews();
                if (loaded != null) { edits.reset(loaded); values = edits.display(); }
                if (wifi != null) networkProblem(wifi);
                else if (problem != null) message(status, problem, true);
                else pickRepository(account, stage);
            } });
        } });
    }
    /**
     * Step two: the account's repositories that mention NEMESIS, with Show All for the rest (then a
     * search box when they're many); tapping one asks to link it.
     */
    private void pickRepository(final Updater.Account account, final LinearLayout stage) {
        stage.removeAllViews();
        int count = 0;
        for (Repos.Listed l : account.repos) if (l.nemesis) count++;
        final int nemesis = count, total = account.repos.size();
        final boolean[] all = {nemesis == 0};
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(0, dp(4), 0, dp(4));
        top.addView(iconButton(backMark(ink, dp(28)), "Back to the GitHub user", new View.OnClickListener() {
            @Override public void onClick(View v) { askOwner(stage, ""); }
        }), new LinearLayout.LayoutParams(dp(56), dp(52)));
        final TextView heading = text("", 22, ink); heading.setPadding(dp(14), 0, dp(8), 0);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        final Button showAll = button("Show All " + total, "List every repository " + account.login + " has", null);
        showAll.setTextSize(20); enable(showAll, true); showAll.setPadding(dp(24), 0, dp(24), 0);
        top.addView(showAll, new LinearLayout.LayoutParams(-2, dp(56)));
        stage.addView(top);
        final EditText filter = field("Search " + account.login + "'s repositories");
        stage.addView(filter, new LinearLayout.LayoutParams(-1, dp(64)));
        final TextView hint = text("", 19, muted); hint.setGravity(Gravity.TOP); hint.setPadding(0, dp(8), 0, dp(4)); stage.addView(hint);
        final LinearLayout list = column(); stage.addView(list);
        final TextView more = text("", 19, muted); more.setPadding(0, dp(6), 0, 0); stage.addView(more);
        // Repositories something here is already linked to get a green link mark.
        final Map<String, List<String>> linked = new HashMap<>();
        Map<String, SelfUpdate.Package> installed = Updater.installed();
        Properties sources = sources();
        for (String id : new TreeSet<>(sources.stringPropertyNames())) {
            String repo = sources.getProperty(id).toLowerCase(Locale.US);
            if (!linked.containsKey(repo)) linked.put(repo, new ArrayList<String>());
            linked.get(repo).add(installed.containsKey(id) ? installed.get(id).name : id);
        }
        final Runnable show = new Runnable() { @Override public void run() {
            boolean every = all[0], search = every && total > REPOS_SHOWN;
            showAll.setVisibility(every || total == nemesis ? View.GONE : View.VISIBLE);
            filter.setVisibility(search ? View.VISIBLE : View.GONE);
            String typed = search ? filter.getText().toString().trim() : "";
            // The user or organization's name in bold, then what's listed.
            android.text.SpannableString said = new android.text.SpannableString(account.login + (total == 0 ? " has no public repositories."
                : !every ? ", " + nemesis + (total > nemesis ? " of " + total + " repositories" : nemesis == 1 ? " repository" : " repositories") + (nemesis == 1 ? " mentions" : " mention") + " NEMESIS:"
                : nemesis == 0 ? ", " + total + (total == 1 ? " repository" : " repositories") + " (none mention NEMESIS):"
                : ", all " + total + " repositories:"));
            said.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, account.login.length(), 0);
            heading.setText(said);
            List<Repos.Listed> shown = Repos.matching(account.repos, typed, every);
            hint.setText(!typed.isEmpty() && shown.isEmpty() ? "No repository name contains “" + typed + "”." : "");
            hint.setVisibility(hint.getText().length() == 0 ? View.GONE : View.VISIBLE);
            list.removeAllViews();
            int cap = search ? REPOS_SHOWN : shown.size();
            for (int i = 0; i < Math.min(shown.size(), cap); i++) {
                final Repos.Listed l = shown.get(i);
                // The whole row picks the repository.
                LinearLayout row = column(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(64)); row.setPadding(dp(8), dp(6), dp(8), dp(6));
                TextView title = text(l.name, 22, ink);
                final List<String> already = linked.get((account.login + "/" + l.name).toLowerCase(Locale.US));
                final boolean isLinked = already != null;
                if (isLinked) { title.setCompoundDrawablesWithIntrinsicBounds(null, null, linkMark(0xff34d27b, dp(26)), null); title.setCompoundDrawablePadding(dp(10)); }
                row.addView(title, new LinearLayout.LayoutParams(-2, -2));
                if (!l.description.isEmpty()) { TextView d = text(l.description, 17, muted); d.setMaxLines(2); d.setEllipsize(android.text.TextUtils.TruncateAt.END); row.addView(d); }
                row.setBackground(drawable("bt_list_selector", selector(false))); row.setClickable(true); row.setFocusable(true);
                row.setContentDescription(l.name + (l.description.isEmpty() ? "" : ", " + l.description.replaceFirst("\\.$", "")) + (isLinked ? ". Already linked." : ". Links the packages in its latest release."));
                row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    hideKeyboard(filter);
                    // Already linked: nothing to read from GitHub.
                    if (isLinked) new Steps("").done("Nothing to Link", join(already) + (already.size() == 1 ? " is" : " are") + " already linked to " + account.login + "/" + l.name
                        + ". To unlink " + (already.size() == 1 ? "it" : "them") + ", use the bin next to " + (already.size() == 1 ? "it" : "each one") + " in Package Manager.", false, null);
                    else confirmLink(account.login, l.name);
                } });
                list.addView(row);
                View rule = new View(TweaksActivity.this); rule.setBackgroundColor(line); list.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
            }
            int rest = shown.size() - cap;
            more.setText(rest > 0 ? rest + " more aren't shown. Type more of the name to narrow the list." : "");
            more.setVisibility(rest > 0 ? View.VISIBLE : View.GONE);
        } };
        showAll.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { all[0] = true; show.run(); } });
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
    /**
     * Reads the repository's latest release in its own window, says what linking does (link what's
     * installed here, install what isn't), then does it there and says what happened.
     */
    private void confirmLink(final String owner, final String name) {
        final Steps w = new Steps("Link or Install from " + owner + "/" + name);
        w.working("Reading " + owner + "/" + name + "'s latest release…");
        final String current = installedVersion(); final Properties sources = sources();
        worker.execute(new Runnable() { @Override public void run() {
            Updater.NetworkProblem offline = Updater.networkProblem(TweaksActivity.this);
            Updater.Repo found = null; Map<String, SelfUpdate.Package> here = null; String failed = null;
            if (offline == null) try { found = Updater.find(TweaksActivity.this, current, owner, name); here = Updater.installed(); }
            catch (Updater.NetworkProblem e) { offline = e; }
            catch (Exception e) { failed = failure("check", e).replace("Couldn't check for updates. ", ""); }
            final Updater.Repo repo = found; final Map<String, SelfUpdate.Package> installed = here; final String problem = failed; final Updater.NetworkProblem wifi = offline;
            main.post(new Runnable() { @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (wifi != null) { w.dialog.dismiss(); networkProblem(wifi); }
                else if (problem != null) w.done("Couldn't Read " + name, problem, true, null);
                else planLink(w, repo, installed, sources);
            } });
        } });
    }
    private void planLink(final Steps w, final Updater.Repo repo, Map<String, SelfUpdate.Package> installed, Properties sources) {
        final List<Updater.Offer> link = new ArrayList<>(), fresh = new ArrayList<>();
        android.text.SpannableStringBuilder said = new android.text.SpannableStringBuilder();
        if (repo.packages.isEmpty()) bold(said.append("The latest release of "), repo.name).append(" has no NEMESIS package Tilepad can read.");
        for (Updater.Offer o : repo.packages) {
            if (said.length() > 0) said.append("\n\n");
            SelfUpdate.Package p = installed.get(o.id);
            if (o.id.equals(SelfUpdate.TILEPAD)) said.append("This is Tilepad's repo. It cannot be unlinked.");
            else if (p != null && p.locked) said.append(o.name).append(" is part of NEMESIS and updates with it.");
            else if (p != null && o.installed != null && repo.name.equals(sources.getProperty(o.id))) bold(said.append(o.name).append(" is already linked to "), repo.name).append('.');
            else if (p != null && o.installed != null) {
                link.add(o);
                bold(said.append(o.name).append(' ').append(o.installed).append(" is already installed on the car. Linking it lets Package Manager update it from the GitHub repo: "), repo.name)
                    .append(". Nothing new will be installed.");
            } else if (o.installable()) {
                // Linked when it's installed, so the check doesn't list what the user only looked at.
                o.installed = null; fresh.add(o);
                bold(said.append(o.name).append(' ').append(o.version).append(" isn't installed on the car yet. It will be installed with root access from the latest release of the GitHub repo "), repo.name)
                    .append(", so only install software you trust. Package Manager can then keep it up to date.");
            } else said.append(o.name).append(" (").append(o.id).append(") isn't installed on the car, and this release can't be installed from Package Manager. Install it from USB instead.");
        }
        if (link.isEmpty() && fresh.isEmpty()) { w.done("Nothing to Link", said, false, null); return; }
        if (!fresh.isEmpty()) said.append("\n\n").append(INSTALL_WARNING);
        w.ask("Link or Install from " + repo.name, said, fresh.isEmpty() ? "Link" : "Link and Install",
            new Runnable() { @Override public void run() { link(w, repo.name, link, fresh); } });
    }
    /** Appends a repository's owner/name in bold. */
    private static android.text.SpannableStringBuilder bold(android.text.SpannableStringBuilder to, String repo) {
        int at = to.length(); to.append(repo);
        to.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), at, to.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return to;
    }
    /** What every install's confirmation says before anything is downloaded. */
    private static final String INSTALL_WARNING = "Installing will take several minutes and reboot the head unit. Infotainment and any camera feeds will be unavailable during this time. Only proceed with installation once safely parked, and keep the car on until the screen is back to normal.";
    private void link(final Steps w, final String repo, final List<Updater.Offer> link, final List<Updater.Offer> fresh) {
        w.working("Linking…");
        worker.execute(new Runnable() { @Override public void run() {
            Properties read = null; String failed = null;
            List<Updater.Offer> both = new ArrayList<>(link); both.addAll(fresh);
            try { for (Updater.Offer o : both) read = Backend.run("update-source", "add", o.id, repo); } catch (Exception e) { failed = e.getMessage(); }
            final Properties loaded = read; final String problem = failed;
            main.post(new Runnable() { @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (loaded != null) edits.reset(loaded);
                if (problem != null) { w.done("Couldn't Link", problem, true, null); render(); return; }
                List<String> linked = new ArrayList<>(), installing = new ArrayList<>();
                for (Updater.Offer o : link) linked.add(o.name);
                for (Updater.Offer o : fresh) installing.add(o.name + " " + o.version);
                Runnable close = new Runnable() { @Override public void run() { closeSources(); } };
                if (fresh.isEmpty()) {
                    w.done("Linked", "Linked " + join(linked) + " to " + repo + ". " + (linked.size() == 1 ? "It" : "They")
                        + " will now show in the Package Manager list and be included in update checks.", false, close);
                    return;
                }
                w.done("Installing", (linked.isEmpty() ? "" : "Linked " + join(linked) + ". ") + "Downloading and installing " + join(installing)
                    + " now. When installation is complete, the screen will restart on its own. Keep the car on until then.", false, null);
                closeSources();
                install(fresh);
            } });
        } });
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
        dialog("Clear Home?", words("Charge-Away stops changing **Scheduled Charging** until you set Home again. Your current **Scheduled Charging** settings will be left untouched."))
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
    private AlertDialog.Builder dialog(String title, View body) { return dialog(heading(title), body); }
    private AlertDialog.Builder dialog(TextView heading, View body) {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return new AlertDialog.Builder(this, night ? AlertDialog.THEME_HOLO_DARK : AlertDialog.THEME_HOLO_LIGHT).setCustomTitle(heading).setView(body);
    }
    private TextView heading(String title) {
        TextView heading = text(title, 26, ink); heading.setTypeface(null, Typeface.BOLD); heading.setGravity(Gravity.CENTER);
        heading.setPadding(dp(28), dp(18), dp(28), dp(8));
        return heading;
    }
    /**
     * One window for a step that asks, then shows a spinner while it works, then says what happened: its
     * title, words and buttons change in place, so the outcome shows where the question was.
     */
    private final class Steps {
        final AlertDialog dialog;
        private final TextView title;
        private final LinearLayout body = column();
        private Runnable confirmed, other, closed;
        Steps(String heading) {
            title = heading(heading);
            body.setPadding(dp(30), dp(12), dp(30), dp(22));
            ScrollView scroll = new ScrollView(TweaksActivity.this); scroll.addView(body);
            dialog = dialog(title, scroll).setPositiveButton(" ", null).setNeutralButton(" ", null).setNegativeButton("Cancel", null).create();
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() { @Override public void onDismiss(DialogInterface d) { if (closed != null) closed.run(); } });
            dialog.show();
            // These two run their step instead of closing the window; Cancel and Close close it.
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                if (confirmed == null) dialog.dismiss(); else confirmed.run();
            } });
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (other != null) other.run(); } });
        }
        void ask(String heading, CharSequence message, String confirm, Runnable then) { ask(heading, message, confirm, then, null, null); }
        void ask(String heading, CharSequence message, String confirm, Runnable then, String alt, Runnable altThen) {
            title.setText(heading); say(message, false); confirmed = then; other = altThen;
            buttons(confirm, alt, true);
        }
        void working(String label) {
            body.removeAllViews();
            LinearLayout row = new LinearLayout(TweaksActivity.this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(6), 0, dp(6));
            ProgressBar spinner = new ProgressBar(TweaksActivity.this); spinner.setIndeterminate(true); row.addView(spinner, new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView doing = text(label, 22, ink); doing.setPadding(dp(18), 0, 0, 0); row.addView(doing, new LinearLayout.LayoutParams(0, -2, 1));
            body.addView(row);
            buttons(null, null, false);
        }
        /** What happened; onClose (or null) runs when the window closes. */
        void done(String heading, CharSequence message, boolean problem, Runnable onClose) {
            title.setText(heading); say(message, problem); confirmed = null; other = null; closed = onClose;
            buttons("Close", null, false);
        }
        private void say(CharSequence message, boolean problem) {
            body.removeAllViews();
            TextView v = text("", 22, problem ? 0xffb54b30 : ink); v.setText(styled(message)); v.setGravity(Gravity.TOP); v.setLineSpacing(dp(3), 1f); body.addView(v);
        }
        private void buttons(String positive, String neutral, boolean cancel) {
            show(DialogInterface.BUTTON_POSITIVE, positive); show(DialogInterface.BUTTON_NEUTRAL, neutral); show(DialogInterface.BUTTON_NEGATIVE, cancel ? "Cancel" : null);
            // While it works: no buttons, and Back can't close it.
            boolean any = positive != null || neutral != null || cancel;
            ((View) dialog.getButton(DialogInterface.BUTTON_POSITIVE).getParent()).setVisibility(any ? View.VISIBLE : View.GONE);
            dialog.setCancelable(any);
        }
        private void show(int which, String label) {
            Button b = dialog.getButton(which); b.setText(label == null ? "" : label); b.setVisibility(label == null ? View.GONE : View.VISIBLE);
        }
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
    /** A button showing a drawn mark (bin, back) instead of words. */
    private ImageButton iconButton(Drawable mark, String description, View.OnClickListener click) {
        ImageButton b = new ImageButton(this); b.setImageDrawable(mark); b.setBackground(buttonBackground()); b.setContentDescription(description); b.setOnClickListener(click);
        return b;
    }
    /** Two columns of one row in the middle pane, each a button or a line of text, sized by weight. */
    private void columns(View left, float leftWeight, View right, float rightWeight) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(24), dp(4), dp(24), dp(8));
        View[] views = {left, right}; float[] weights = {leftWeight, rightWeight};
        for (int i = 0; i < 2; i++) {
            View v = views[i];
            // A line of text, or a button (perhaps inside spinner()'s frame).
            boolean words = v instanceof TextView && !(v instanceof Button);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, words ? -2 : dp(64), weights[i]);
            p.setMargins(dp(6), dp(6), dp(6), dp(6));
            if (words) v.setPadding(0, 0, dp(8), 0);
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
    /** Already linked: two chain links at an angle. */
    private Drawable linkMark(final int color, final int size) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.RectF link = new android.graphics.RectF();
            @Override public void draw(Canvas canvas) {
                Rect b = getBounds(); float cx = b.exactCenterX(), cy = b.exactCenterY(), s = b.width() / 2f, h = s * .34f;
                paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.5f));
                canvas.save();
                canvas.rotate(-45, cx, cy);
                link.set(cx - s * .95f, cy - h, cx + s * .15f, cy + h); canvas.drawRoundRect(link, h, h, paint);
                link.set(cx - s * .15f, cy - h, cx + s * .95f, cy + h); canvas.drawRoundRect(link, h, h, paint);
                canvas.restore();
            }
            @Override public int getIntrinsicWidth() { return size; }
            @Override public int getIntrinsicHeight() { return size; }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
    /** Back: a left arrow, in the label's colour. */
    private Drawable backMark(final int color, final int size) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();
            @Override public void draw(Canvas canvas) {
                Rect b = getBounds(); float cx = b.exactCenterX(), cy = b.exactCenterY(), s = b.width() / 2f;
                paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.5f));
                paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
                path.reset();
                path.moveTo(cx + s * .7f, cy); path.lineTo(cx - s * .7f, cy);
                path.moveTo(cx - s * .1f, cy - s * .6f); path.lineTo(cx - s * .7f, cy); path.lineTo(cx - s * .1f, cy + s * .6f);
                canvas.drawPath(path, paint);
            }
            @Override public int getIntrinsicWidth() { return size; }
            @Override public int getIntrinsicHeight() { return size; }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
    /** Package Manager's delete: a bin with a lid and handle, in the label's colour. */
    private Drawable trashMark(final int color, final int size) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();
            @Override public void draw(Canvas canvas) {
                Rect b = getBounds(); float stroke = dp(2.5f), cx = b.exactCenterX(), s = b.width() / 2f;
                float lid = b.top + s * .38f, bottom = b.bottom - s * .12f;
                paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke);
                paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
                path.reset();
                path.moveTo(cx - s * .8f, lid); path.lineTo(cx + s * .8f, lid);
                path.moveTo(cx - s * .3f, lid); path.lineTo(cx - s * .3f, b.top + s * .14f); path.lineTo(cx + s * .3f, b.top + s * .14f); path.lineTo(cx + s * .3f, lid);
                path.moveTo(cx - s * .62f, lid); path.lineTo(cx - s * .5f, bottom); path.lineTo(cx + s * .5f, bottom); path.lineTo(cx + s * .62f, lid);
                path.moveTo(cx - s * .2f, lid + s * .3f); path.lineTo(cx - s * .17f, bottom - s * .25f);
                path.moveTo(cx + s * .2f, lid + s * .3f); path.lineTo(cx + s * .17f, bottom - s * .25f);
                canvas.drawPath(path, paint);
            }
            @Override public int getIntrinsicWidth() { return size; }
            @Override public int getIntrinsicHeight() { return size; }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
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
