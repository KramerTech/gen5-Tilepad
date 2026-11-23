package com.tilepad.tweaks.addon;

import android.content.SharedPreferences;
import com.tilepad.tweakcore.AddonCtl;
import com.tilepad.tweakcore.AddonPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The pages installed packages describe for themselves (AddonPage), each an entry in the host's
 * left pane after Tilepad's own tweaks. A page is read once a visit (load), what it shows every 2 s
 * while it's shown (tick), and a change is sent through the package's own ctl.sh (AddonCtl).
 *
 * All of that runs on this class's own worker, not the host's: the host's worker reads Tilepad's
 * settings and then runs the compatibility audit, which hashes several system files, so a page
 * queued behind it would wait seconds for work it doesn't need.
 */
public final class Addons {
    private static final String PREFIX = "addon:";
    /** The entries of the last load, kept so the left pane doesn't change while the next one runs. */
    private static final String KEPT = "addons";

    /** One left-pane entry. */
    public static final class Entry {
        public final String id, title, help;
        Entry(String id, String title, String help) { this.id = id; this.title = title; this.help = help; }
    }

    private final PageHost host;
    private final Widgets w;
    private final AddonCtl ctl = new AddonCtl();
    /** Runs the page reads, changes and requests in order; shut down with close(). */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** The pages of the last load; null until one finishes. */
    private List<AddonCtl.Loaded> loaded;
    private Properties values = new Properties();
    /** Bumped by every change made here; a read that started before the latest one isn't shown. */
    private int editSeq;
    private boolean loading, readInFlight;
    /** The action whose request is waiting for its answer; null when none is. */
    private AddonPage.Control running;

    public Addons(PageHost host) {
        this.host = host;
        this.w = new Widgets(host);
    }

    /** Stops the worker once the host closes; a read still running finishes and isn't shown. */
    public void close() { worker.shutdown(); }

    /** The left-pane entries: those of the last load, or until one finishes, those kept from the visit before. */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        if (loaded != null) {
            for (AddonCtl.Loaded l : loaded) if (l.page != null) out.add(new Entry(PREFIX + l.id, l.page.title, help(l.page)));
            return out;
        }
        for (String line : prefs().getString(KEPT, "").split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) out.add(new Entry(line.substring(0, tab), line.substring(tab + 1), ""));
        }
        return out;
    }

    /** Whether the host's left-pane entry id is a package's page. */
    public boolean owns(String id) { return id != null && id.startsWith(PREFIX); }

    /** Reads every package's page on this class's worker, then redraws the window if the entries changed. */
    public void load() {
        if (loading) return;
        loading = true;
        // taken before the reads start, as tick does: a setting changed meanwhile isn't overwritten
        final int startedAt = editSeq;
        worker.execute(new Runnable() { @Override public void run() {
            final List<AddonCtl.Loaded> fresh = ctl.load(AddonCtl.BUILT_IN);
            for (AddonCtl.Loaded l : fresh) {
                if (l.page == null) android.util.Log.w("Tilepad", "package " + l.id + ": its page isn't shown: " + l.problem);
            }
            final Properties read = ctl.read(names(fresh));
            host.main().post(new Runnable() { @Override public void run() {
                loading = false;
                if (host.activity().isFinishing()) return;
                List<String> before = ids(entries());
                loaded = fresh;
                if (startedAt == editSeq) values = read;
                keep();
                if (!before.equals(ids(entries()))) host.render();
                else if (host.ready()) host.renderInPlace();
            } });
        } });
    }

    /** Every 2 s while a package's page is shown: reads what it shows again, and redraws only if that changed. */
    public void tick() {
        if (readInFlight || loaded == null) return;
        readInFlight = true;
        final int startedAt = editSeq;
        final List<AddonCtl.Loaded> pages = loaded;
        worker.execute(new Runnable() { @Override public void run() {
            final Properties read = ctl.read(names(pages));
            host.main().post(new Runnable() { @Override public void run() {
                readInFlight = false;
                if (host.activity().isFinishing() || startedAt != editSeq || read.equals(values)) return;
                values = read;
                if (host.ready()) host.renderInPlace();
            } });
        } });
    }

    /** Draws entry id's page into the host's middle column. */
    public void draw(String id) {
        AddonCtl.Loaded l = find(id);
        if (l == null) {
            w.note(loaded == null ? "Reading this package's page…" : "This package's page isn't installed any more.", false);
            return;
        }
        if (l.page == null) { w.note("This package's page can't be shown: " + l.problem + ".", true); return; }
        AddonPage p = l.page;
        for (AddonPage.Status s : p.status) if (p.holds(s.when, values)) w.note(p.fill(s.text, values), s.problem);
        for (final AddonPage.Control c : p.controls) {
            if (!p.holds(c.when, values)) continue;
            draw(p, c);
        }
        if (!p.problems.isEmpty())
            w.note("Some of this page couldn't be shown, so its package may need an update (" + p.problems.size() + " skipped).", true);
    }

    private void draw(final AddonPage p, final AddonPage.Control c) {
        final String now = p.value(values, c.prop == null ? "" : c.prop);
        switch (c.type) {
            case HEADING: w.heading(c.label); return;
            case NOTE: w.note(p.fill(c.text, values), c.problem); return;
            case TOGGLE: {
                final boolean on = c.on.equals(now);
                w.toggle(c.label, on, new Runnable() {
                    @Override public void run() { set(p, c, on ? c.off : c.on); }
                });
                return;
            }
            case STEPPER: {
                int v;
                try { v = Integer.parseInt(now); } catch (NumberFormatException e) { v = Integer.parseInt(c.def); }
                final int current = Math.max(c.min, Math.min(c.max, v));
                w.stepper(c.label, "Lower: " + c.label, "Raise: " + c.label, AddonPage.reading(c, current),
                    new Runnable() { @Override public void run() { if (current > c.min) set(p, c, String.valueOf(Math.max(c.min, current - c.step))); } },
                    new Runnable() { @Override public void run() { if (current < c.max) set(p, c, String.valueOf(Math.min(c.max, current + c.step))); } });
                return;
            }
            case TEMPERATURE: {
                double v;
                try { v = Double.parseDouble(now); } catch (NumberFormatException e) { v = Double.parseDouble(c.def); }
                final double current = Math.max(c.min, Math.min(c.max, v));
                final boolean fahrenheit = c.unitprop == null || c.unitprop.isEmpty() || !"C".equalsIgnoreCase(p.value(values, c.unitprop));
                w.stepper(c.label, "Lower: " + c.label, "Raise: " + c.label, AddonPage.temperature(current, fahrenheit),
                    new Runnable() { @Override public void run() { set(p, c, AddonPage.stepTemperature(c, current, -1, fahrenheit)); } },
                    new Runnable() { @Override public void run() { set(p, c, AddonPage.stepTemperature(c, current, 1, fahrenheit)); } });
                return;
            }
            case COLOR: {
                final AddonPage.Palette palette = p.palette(c, values);
                final String chosen = now.matches("[0-9A-F]{6}") ? now : c.def;
                String name = palette == null ? "#" + chosen : palette.nameOf(chosen);
                w.colorRow(c.label, Integer.parseInt(chosen, 16), name, palette == null ? null : new Runnable() {
                    @Override public void run() {
                        w.colorGrid("Choose " + c.label, palette.colors, palette.names, palette.presetHeading, palette.presets,
                            palette.presetNames, chosen, new Widgets.ChoiceListener() {
                                @Override public void chose(int index) {
                                    int preset = index - palette.colors.size();
                                    if (index >= 0 && index < palette.colors.size()) set(p, c, palette.colors.get(index));
                                    else if (preset >= 0 && preset < palette.presets.size()) set(p, c, palette.presets.get(preset));
                                }
                            });
                    }
                });
                return;
            }
            case ACTION: {
                w.actionButton(running == null ? c.label : (running == c ? "Working…" : c.label), running == null, new Runnable() {
                    @Override public void run() {
                        if (c.confirmTitle == null) { request(p, c); return; }
                        host.confirm(c.confirmTitle, c.confirmText == null ? "" : c.confirmText,
                            c.confirmButton == null ? c.label : c.confirmButton,
                            new Runnable() { @Override public void run() { request(p, c); } });
                    }
                });
                return;
            }
            default: { // CHOICES
                String[] labels = new String[c.options.size()];
                int chosen = -1;
                for (int i = 0; i < labels.length; i++) {
                    labels[i] = c.options.get(i).label;
                    if (c.options.get(i).value.equals(now)) chosen = i;
                }
                if (c.label != null) w.note(c.label, false);
                Boolean layout = c.vertical ? Boolean.TRUE : c.horizontal ? Boolean.FALSE : null;
                w.choices(labels, chosen, layout, new Widgets.ChoiceListener() {
                    @Override public void chose(int index) {
                        if (index >= 0 && index < c.options.size()) set(p, c, c.options.get(index).value);
                    }
                });
                if (chosen >= 0 && c.options.get(chosen).note != null) w.note(c.options.get(chosen).note, false);
            }
        }
    }

    /**
     * A change that shows at once; the write and a fresh read then run on the worker in tap order.
     * A write that didn't take says so in a window, and the read shows the setting as it is.
     */
    private void set(final AddonPage p, final AddonPage.Control c, final String value) {
        if (c.type != AddonPage.Type.TOGGLE && value.equals(p.value(values, c.prop))) return;
        values.setProperty(c.prop, value);
        final int seq = ++editSeq;
        host.renderInPlace();
        final List<AddonCtl.Loaded> pages = loaded;
        worker.execute(new Runnable() { @Override public void run() {
            final String problem = AddonCtl.setProblem(ctl.set(p, c.prop, value));
            final Properties read = ctl.read(names(pages));
            host.main().post(new Runnable() { @Override public void run() {
                if (host.activity().isFinishing()) return;
                if (seq == editSeq && !read.equals(values)) { values = read; host.renderInPlace(); }
                if (problem != null) host.tell("That Change Didn't Take", problem);
            } });
        } });
    }

    /**
     * Sends action c's request through its package's ctl.sh, then waits (up to its timeout) for the
     * answer in the page's results property and says what happened, in the page's own words when it has them.
     * One request at a time: the other buttons wait while it runs.
     */
    private void request(final AddonPage p, final AddonPage.Control c) {
        if (running != null) return;
        running = c;
        host.renderInPlace();
        final List<AddonCtl.Loaded> pages = loaded;
        worker.execute(new Runnable() { @Override public void run() {
            String word = null;
            long seq = ctl.req(p, c.request);
            if (seq >= 0) {
                long end = android.os.SystemClock.uptimeMillis() + c.timeoutS * 1000L;
                while (word == null && android.os.SystemClock.uptimeMillis() < end) {
                    android.os.SystemClock.sleep(500);
                    word = AddonCtl.result(ctl.prop(p.results), c.request, seq);
                }
            }
            final String said = AddonCtl.said(c, seq, word);
            final Properties read = ctl.read(names(pages));
            host.main().post(new Runnable() { @Override public void run() {
                running = null;
                if (host.activity().isFinishing()) return;
                values = read;
                host.renderInPlace();
                host.tell(c.label, said);
            } });
        } });
    }

    private AddonCtl.Loaded find(String id) {
        if (loaded == null || !owns(id)) return null;
        for (AddonCtl.Loaded l : loaded) if (id.equals(PREFIX + l.id)) return l;
        return null;
    }

    /** The help shown beside a page: the package's own, and which package it comes from. */
    static String help(AddonPage p) {
        String own = p.help.isEmpty() ? "" : p.help + "\n\n";
        return own + "This page comes from the installed package " + p.id + ", and shows while it's installed.";
    }

    private static List<String> names(List<AddonCtl.Loaded> pages) {
        List<String> out = new ArrayList<>();
        if (pages != null) for (AddonCtl.Loaded l : pages) if (l.page != null) out.addAll(l.page.reads());
        return out;
    }

    private static List<String> ids(List<Entry> entries) {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) out.add(e.id);
        return out;
    }

    private void keep() {
        StringBuilder out = new StringBuilder();
        for (Entry e : entries()) out.append(e.id).append('\t').append(e.title.replace('\n', ' ')).append('\n');
        prefs().edit().putString(KEPT, out.toString()).apply();
    }

    private SharedPreferences prefs() { return host.activity().getSharedPreferences("addons", 0); }
}
