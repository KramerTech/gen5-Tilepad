package com.tilepad.tweaks;

import com.tilepad.tweakcore.Config;
import java.util.*;

/** One active batch and the latest value of each edited key; never a backlog of obsolete snapshots. */
final class SettingsEdits {
    private Properties confirmed = new Properties();
    private final LinkedHashMap<String, String> pending = new LinkedHashMap<>();
    private Map<String, String> active;
    synchronized void reset(Properties loaded) {
        if (hasWork()) throw new IllegalStateException("Cannot replace settings during a save");
        confirmed = (Properties) loaded.clone();
    }
    synchronized Properties confirmed() { return (Properties) confirmed.clone(); }
    synchronized Properties display() {
        Properties result = confirmed();
        if (active != null) result.putAll(active);
        result.putAll(pending);
        return result;
    }
    synchronized void edit(String key, String value) {
        Properties baseline = confirmed(); if (active != null) baseline.putAll(active);
        Config config = new Config(baseline); String before = config.get(key); config.set(key, value);
        if (before.equals(value)) pending.remove(key); else pending.put(key, value);
    }
    synchronized boolean hasWork() { return active != null || !pending.isEmpty(); }
    synchronized String[] take() {
        if (active != null) throw new IllegalStateException("A save is already active");
        if (pending.isEmpty()) return null;
        active = new LinkedHashMap<>(pending); pending.clear();
        String[] args = new String[1 + active.size() * 2]; args[0] = "update";
        int i = 1;
        for (Map.Entry<String, String> entry : active.entrySet()) { args[i++] = entry.getKey(); args[i++] = entry.getValue(); }
        return args;
    }
    synchronized void finish(Properties loaded) {
        active = null;
        if (loaded == null) { pending.clear(); return; }
        confirmed = (Properties) loaded.clone();
        Config config = new Config(confirmed);
        Iterator<Map.Entry<String, String>> entries = pending.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, String> entry = entries.next();
            if (config.get(entry.getKey()).equals(entry.getValue())) entries.remove();
        }
    }
}
