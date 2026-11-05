package com.tilepad.migration;

import com.tilepad.tweakcore.*;
import java.io.*;
import java.util.*;

/** Explicit one-time adoption of the known legacy handle-only patch. Never writes /system. */
public final class LegacyNavMigration {
    public static void adopt(File state, File recipes, byte[] candidate,
                             final PatchManager.SystemOps readOnly, String boot) throws Exception {
        Properties recipe = Files.properties(new File(recipes, "right.properties"));
        require(Files.hash(candidate).equals(recipe.getProperty("stockSha256")), "Stock candidate does not match the installed recipe");
        File target = readOnly.file(recipe.getProperty("path"));
        byte[] before = Files.read(target);
        require(Files.hash(readOnly.file(recipe.getProperty("apkPath")))
            .equals(recipe.getProperty("apkSha256")), "Paired APK differs from the supported capture");
        Config navOnly = new Config(new Properties()); navOnly.set("nav", "true");
        byte[] expected = PatchManager.transform(recipe, candidate, navOnly);
        require(Arrays.equals(before, expected), "Current ODEX is not exactly the known handle-only patch");
        require(!new File(state, "pending.properties").exists(), "Resolve the existing pending transaction before migration");
        File configFile = new File(state, "config.properties");
        Config original = new Config(Files.properties(configFile));
        require(!original.enabled("popover"), "Pop-over is already enabled; this migration only adopts the legacy handle patch");
        File folder = new File(state, "files/" + target.getName());
        File stock = new File(folder, "stock"), journalFile = new File(folder, "journal.properties");
        Properties journal = Files.properties(journalFile);
        if (stock.exists()) require(Arrays.equals(Files.read(stock), candidate), "A different stock backup already exists");
        if (journalFile.exists()) {
            require(recipe.getProperty("path").equals(journal.getProperty("path"))
                && Files.hash(before).equals(journal.getProperty("current")) && !journal.containsKey("next"),
                "An incompatible or pending journal already exists");
        }
        // Before creating ownership for the right panel, verify that the user's
        // other enabled static tweaks are already settled. The supplied adapter
        // must reject remounts and writes rather than repairing anything here.
        final PatchManager manager = new PatchManager(state, recipes, readOnly);
        if (!stock.exists()) manager.apply(original, boot);

        File audit = new File(state, "legacy-nav-migration");
        keep(new File(audit, "previous-config.properties"), configFile.isFile() ? Files.read(configFile) : new byte[0]);
        keep(new File(audit, "legacy.odex"), before);
        keep(stock, candidate);
        journal.setProperty("path", recipe.getProperty("path"));
        journal.setProperty("current", Files.hash(before));
        Files.store(journalFile, journal);

        Config next = new Config((Properties) original.values.clone());
        next.set("nav", "true");
        new Transaction(state, new Transaction.Apply() {
            @Override public void run(Config c, Config previous, boolean rollback) throws Exception { manager.apply(c, previous, boot); }
        }).commit(next);
        require(Arrays.equals(before, Files.read(target)), "System file unexpectedly changed");
        manager.check("nav", next); manager.check("popover", next);
        Properties result = new Properties();
        result.setProperty("stockSha256", Files.hash(candidate));
        result.setProperty("legacySha256", Files.hash(before));
        result.setProperty("systemFileChanged", "false");
        result.setProperty("nav", "true"); result.setProperty("popover", "false");
        result.setProperty("boot", boot);
        Files.store(new File(audit, "complete.properties"), result);
    }
    private static void keep(File file, byte[] bytes) throws IOException {
        if (!file.exists()) Files.write(file, bytes);
        // An earlier interrupted attempt's audit is deliberately retained.
        else if (!file.getName().equals("previous-config.properties"))
            require(Arrays.equals(Files.read(file), bytes), "Existing migration backup differs: " + file);
    }
    private static void require(boolean ok, String why) throws IOException { if (!ok) throw new IOException(why); }
    private LegacyNavMigration() { }
}
