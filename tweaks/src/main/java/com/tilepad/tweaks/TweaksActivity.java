package com.tilepad.tweaks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.tilepad.tweakcore.Config;
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
    private Resources stock;
    private LinearLayout controls;
    private ScrollView railScroll, controlScroll, helpScroll;
    private boolean preview, busy = true, ready;
    private String error;
    private boolean capturingHome;
    /** "Home saved." or "Home updated." after a Home change, until another tweak is selected. */
    private String homeNotice;
    /** Where the compatibility report went (or why it didn't), until another tweak is selected. */
    private String reportNotice;
    private boolean savingReport;
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
    @Override protected void onResume() { super.onResume(); if (ready && !busy && !edits.hasWork()) refresh(); }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); render(); }
    @Override protected void onPause() {
        main.removeCallbacks(saveFlush);
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
        String notice = preview ? "Preview" : edits.hasWork() ? "Saving…" : busy ? (capturingHome || savingReport ? "Saving…" : "Reading settings…") : "true".equals(values.getProperty("restart")) ? "Restart required" : "";
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
            tab.setPadding(dp(14), dp(10), dp(14), dp(10)); tab.setMinHeight(dp(72));
            if (i == selected) selectedTab[0] = tab;
            tab.setSelected(i == selected); tab.setFocusable(true); tab.setClickable(true);
            tab.setBackground(drawable("bt_list_selector", selector(i == selected)));
            tab.setContentDescription(item.railTitle() + (i == selected ? ", selected" : ""));
            tab.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                if (selected == index) return;
                selected = index; homeNotice = null; reportNotice = null; getPreferences(0).edit().putInt("selected", selected).apply();
                controlScroll.scrollTo(0, 0); helpScroll.scrollTo(0, 0); render();
            } });
            rail.addView(tab, new LinearLayout.LayoutParams(-1, -2)); divider(rail);
        }
        controlScroll = scroller("Selected tweak controls"); controls = column(); controlScroll.addView(controls);
        panes.addView(controlScroll, new LinearLayout.LayoutParams(dp(600 * scale), -1)); verticalDivider(panes);
        final TweakInfo item = TweakInfo.RAIL[selected];
        String unavailable = unavailable(item.id);
        boolean enabled = new Config(values).enabled(item.id);
        checkbox(controls, item.title, item.id, enabled, ready && !busy && (unavailable.isEmpty() || enabled));
        String runtime = values.getProperty("status." + item.id, "");
        String described = ready ? item.state(enabled, pending(item.id)) : null;
        if (ready && enabled && item.id.equals("chargeaway") && !new Config(values).hasHome()) described = TweakInfo.CHARGE_AWAY_NO_HOME;
        if (described != null) note(described, false);
        // Then what the tweak is doing right now, if it says.
        if (!runtime.isEmpty() && (described == null || enabled)) note(runtime, false);
        if (error != null) note(error, true);
        if (!unavailable.isEmpty()) note("Unavailable: " + unavailable, true);
        if (item.companion != null) {
            // The nav handle: forced hidden while the pop-over is disabled, otherwise its own choice.
            TweakInfo second = item.companion;
            String secondUnavailable = unavailable(second.id);
            boolean hidden = enabled || new Config(values).enabled(second.id);
            checkbox(controls, second.title, second.id, hidden, ready && !busy && !enabled && (secondUnavailable.isEmpty() || hidden));
            String secondState = !ready ? null : enabled && !pending(second.id)
                ? "The nav handle is always hidden while the right-hand pop-over is disabled." : second.state(hidden, pending(second.id));
            if (secondState != null) note(secondState, false);
            if (!secondUnavailable.isEmpty() && !enabled) note("Unavailable: " + secondUnavailable, true);
        }
        // Firmware this build wasn't made for: offer the details needed to add it.
        boolean incompatible = !unavailable.isEmpty()
            || (item.companion != null && !unavailable(item.companion.id).isEmpty());
        if (ready && incompatible) {
            if (reportNotice != null) note(reportNotice, false);
            Button report = button("Save Compatibility Report to USB", "Save this head unit's firmware details to the USB drive", new View.OnClickListener() {
                @Override public void onClick(View v) { saveReport(); }
            });
            report.setTextSize(22); report.setEnabled(ready && !busy && !edits.hasWork());
            controls.addView(report, new LinearLayout.LayoutParams(-1, dp(72))); divider(controls);
        }
        if (!values.getProperty("runtime", "").isEmpty()) note(values.getProperty("runtime"), true);
        if (item.id.equals("chargeaway")) {
            boolean home = new Config(values).hasHome();
            note(home ? String.format(Locale.US, "Home: %.6f, %.6f", Double.parseDouble(values.getProperty("homeLat")), Double.parseDouble(values.getProperty("homeLon")))
                : "No Home location set.", false);
            if (homeNotice != null) note(homeNotice, false);
            Button mark = button(home ? "Update Home to Current Location" : "Set Current Location as Home",
                home ? "Replace Home with the current GPS location" : "Save the current GPS location as Home", new View.OnClickListener() {
                @Override public void onClick(View v) { captureHome(false); }
            });
            mark.setTextSize(22); mark.setEnabled(ready && !busy && !edits.hasWork());
            controls.addView(mark, new LinearLayout.LayoutParams(-1, dp(72))); divider(controls);
            if (home) {
                Button clear = button("Clear Home", "Remove the saved Home location", new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmClearHome(); }
                });
                clear.setTextSize(22); clear.setEnabled(ready && !busy && !edits.hasWork());
                controls.addView(clear, new LinearLayout.LayoutParams(-1, dp(72))); divider(controls);
            }
        } else if (enabled && item.options) {
            if (item.id.equals("dst")) {
                note("Daylight-saving schedule", false);
                choice("region", new String[]{"us", "eu", "none"}, new String[]{"US / Canada", "Europe", "No daylight saving"});
            } else {
                note("Apply at boot below this outdoor temperature", false);
                LinearLayout temp = new LinearLayout(this); temp.setGravity(Gravity.CENTER_VERTICAL); temp.setPadding(dp(18), 0, dp(18), 0);
                temp.addView(button("−", "Lower temperature threshold", new View.OnClickListener() { @Override public void onClick(View v) { adjustThreshold(-1); } }), new LinearLayout.LayoutParams(dp(62), dp(64)));
                TextView reading = text(thresholdLabel(), 24, ink); reading.setGravity(Gravity.CENTER); temp.addView(reading, new LinearLayout.LayoutParams(0, dp(64), 1));
                temp.addView(button("+", "Raise temperature threshold", new View.OnClickListener() { @Override public void onClick(View v) { adjustThreshold(1); } }), new LinearLayout.LayoutParams(dp(62), dp(64)));
                controls.addView(temp); divider(controls);
                choice("units", new String[]{"F", "C"}, new String[]{"Fahrenheit (°F)", "Celsius (°C)"});
                heater("driver", "Driver seat", "driverLevel"); heater("wheel", "Steering wheel", null); heater("passenger", "Passenger seat (only with a passenger)", "passengerLevel");
            }
        }
        helpScroll = scroller("Feature explanation"); LinearLayout help = column(); helpScroll.addView(help);
        panes.addView(helpScroll, new LinearLayout.LayoutParams(0, -1, 1));
        TextView description = text(item.help, 24, ink); description.setGravity(Gravity.TOP); description.setLineSpacing(dp(3), 1f);
        description.setPadding(dp(30), dp(24), dp(30), dp(30)); help.addView(description, new LinearLayout.LayoutParams(-1, -2));
        final ScrollView railView = railScroll, controlView = controlScroll, helpView = helpScroll;
        railView.post(new Runnable() { @Override public void run() { railView.scrollTo(0, firstLayout ? selectedTab[0].getTop() : railY); controlView.scrollTo(0, controlY); helpView.scrollTo(0, helpY); } });
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
        controls.addView(group); divider(controls);
    }
    private boolean pending(String id) { return "true".equals(values.getProperty("pending." + id)); }
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
        if (preview) { busy = false; ready = true; render(); return; }
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
                try {
                    final Properties full = Backend.run("status");
                    main.post(new Runnable() { @Override public void run() {
                        if (isFinishing()) return;
                        for (String key : full.stringPropertyNames()) if (key.startsWith("unavailable.")) audit.setProperty(key, full.getProperty(key));
                        render();
                    } });
                } catch (Exception ignored) { }
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
    private void saveReport() {
        if (!ready || busy || edits.hasWork()) return;
        busy = true; savingReport = true; reportNotice = null; render();
        worker.execute(new Runnable() { @Override public void run() {
            String notice;
            try {
                String name = preview ? "tilepad-compat-PREVIEW.txt" : Backend.run("report").getProperty("saved", "");
                notice = "Saved " + name + " to the USB drive. To ask for this car to be supported, open a new issue at "
                    + "github.com/KramerTech/gen5-Tilepad/issues on a computer and attach that file.";
            } catch (Exception e) { notice = "Couldn't save the report: " + e.getMessage(); }
            final String result = notice;
            main.post(new Runnable() { @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                busy = false; savingReport = false; reportNotice = result; render();
            } });
        } });
    }
    private void confirmClearHome() {
        new AlertDialog.Builder(this).setTitle("Clear Home?")
            .setMessage("Charge-Away stops changing Scheduled Charging until you set Home again. Scheduled Charging is left as it is.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear Home", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) { captureHome(true); }
            }).show();
    }
    /** Saves the current GPS fix as Home, or clears Home. */
    private void captureHome(final boolean clear) {
        if (!ready || busy || edits.hasWork()) return;
        final boolean hadHome = new Config(values).hasHome();
        busy = true; capturingHome = true; error = null; homeNotice = null; render();
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
                if (result != null) {
                    edits.reset(result);
                    homeNotice = clear ? "Home cleared." : hadHome ? "Home updated to your current location." : "Home saved.";
                }
                busy = false; capturingHome = false; error = problem; render();
            } });
        } });
    }
    private Button button(String label, String description, View.OnClickListener click) {
        Button b = new Button(this); b.setText(label); b.setTextColor(ink); b.setTextSize(26); b.setAllCaps(false); b.setBackground(selector(false)); b.setContentDescription(description); b.setEnabled(ready && !busy); b.setOnClickListener(click); return b;
    }
    private StateListDrawable selector(boolean selected) {
        StateListDrawable d = new StateListDrawable(); d.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(0x3300b5de));
        d.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(0x2200b5de)); d.addState(new int[]{}, new ColorDrawable(selected ? 0x1100b5de : 0x00000000)); return d;
    }
    private void divider(LinearLayout parent) { View v = new View(this); v.setBackgroundColor(line); parent.addView(v, new LinearLayout.LayoutParams(-1, dp(1))); }
    private void verticalDivider(LinearLayout parent) { View v = new View(this); v.setBackgroundColor(line); parent.addView(v, new LinearLayout.LayoutParams(dp(1), -1)); }
}
