package com.tilepad;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Snapshot of launchable activities. Loaded off the UI thread; cached across opens. */
final class AppCatalog {
    static final class Entry {
        final ComponentName component;
        final String key;
        final Drawable icon;
        final boolean launchable;
        String label;

        Entry(ComponentName component, String label, Drawable icon, boolean launchable) {
            this.component = component;
            this.key = component.flattenToShortString();
            this.label = label;
            this.icon = icon;
            this.launchable = launchable;
        }
    }

    /** Activities that declare MAIN + LAUNCHER, i.e. what a normal launcher shows. */
    final List<Entry> apps;
    /** Every exported MAIN activity, including Hyundai/system screens without a launcher icon. */
    final List<Entry> all;
    private final Map<String, Entry> byKey;

    private static volatile AppCatalog cached;

    private AppCatalog(List<Entry> apps, List<Entry> all, Map<String, Entry> byKey) {
        this.apps = apps;
        this.all = all;
        this.byKey = byKey;
    }

    static AppCatalog cached() { return cached; }

    Entry find(String key) { return byKey.get(key); }

    /** Blocking; call from a worker thread. */
    static AppCatalog load(Context context) {
        PackageManager pm = context.getPackageManager();
        String self = context.getPackageName();
        Map<String, Entry> byKey = new LinkedHashMap<>();
        Map<String, File> favicons = NemesisIcons.faviconsByPackage();

        Set<String> launcherKeys = new HashSet<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            Entry e = entry(context, pm, ri, self, favicons, byKey);
            if (e != null) launcherKeys.add(e.key);
        }
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN), 0)) {
            entry(context, pm, ri, self, favicons, byKey);
        }

        List<Entry> all = new ArrayList<>(byKey.values());
        sort(all);
        disambiguate(all);
        List<Entry> apps = new ArrayList<>();
        for (Entry e : all) if (launcherKeys.contains(e.key)) apps.add(e);

        AppCatalog catalog = new AppCatalog(Collections.unmodifiableList(apps),
            Collections.unmodifiableList(all), byKey);
        cached = catalog;
        return catalog;
    }

    private static Entry entry(Context context, PackageManager pm, ResolveInfo ri, String self,
                               Map<String, File> favicons, Map<String, Entry> byKey) {
        ActivityInfo ai = ri.activityInfo;
        if (ai == null || self.equals(ai.packageName)) return null;
        // Non-exported activities would throw SecurityException when we start them.
        if (!ai.exported || !ai.enabled || !ai.applicationInfo.enabled) return null;
        ComponentName cn = new ComponentName(ai.packageName, ai.name);
        String key = cn.flattenToShortString();
        Entry existing = byKey.get(key);
        if (existing != null) return existing;
        CharSequence label = ri.loadLabel(pm);
        // No icon in the APK: the NEMESIS package's favicon, before Android's generic robot.
        Drawable icon = ri.getIconResource() == 0 ? NemesisIcons.drawable(context, favicons.get(ai.packageName)) : null;
        if (icon == null) icon = ri.loadIcon(pm);
        boolean permitted = ai.permission == null
            || pm.checkPermission(ai.permission, self) == PackageManager.PERMISSION_GRANTED;
        String text = label == null ? "" : label.toString().trim();
        if (text.isEmpty()) text = ai.name.substring(ai.name.lastIndexOf('.') + 1);
        Entry e = new Entry(cn, text, icon, permitted);
        byKey.put(key, e);
        return e;
    }

    private static void sort(List<Entry> list) {
        final Collator collator = Collator.getInstance();
        Collections.sort(list, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                int c = collator.compare(a.label, b.label);
                return c != 0 ? c : a.key.compareTo(b.key);
            }
        });
    }

    /** Several Hyundai screens share one label; append the activity's class name to tell them apart. */
    private static void disambiguate(List<Entry> list) {
        Map<String, Integer> counts = new HashMap<>();
        for (Entry e : list) {
            Integer n = counts.get(e.label);
            counts.put(e.label, n == null ? 1 : n + 1);
        }
        for (Entry e : list) {
            Integer n = counts.get(e.label);
            if (n != null && n > 1) {
                String cls = e.component.getClassName();
                e.label = e.label + " · " + cls.substring(cls.lastIndexOf('.') + 1);
            }
        }
    }
}
