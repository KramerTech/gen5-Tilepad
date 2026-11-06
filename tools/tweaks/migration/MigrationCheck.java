package com.tilepad.migration;

import com.tilepad.tweakcore.*;
import java.io.*;
import java.util.*;

/** Exercise adoption, refusal, and subsequent composition/restore on the real capture. */
public final class MigrationCheck {
    public static void main(String[] args) throws Exception {
        final File firmware = new File(args[0]), work = new File(args[1]), recipes = new File(args[2]);
        final File root = new File(work, "system"), state = new File(work, "state");
        Properties recipe = Files.properties(new File(recipes, "right.properties"));
        final File target = new File(root, recipe.getProperty("path").substring(1));
        byte[] stock = Files.read(new File(firmware, "app/RightScreenHome.odex"));
        byte[] apk = Files.read(new File(firmware, "app/RightScreenHome.apk"));
        Config legacy = new Config(new Properties()); legacy.set("nav", "true");
        byte[] current = PatchManager.transform(recipe, stock, legacy);
        Files.write(target, current);
        Files.write(new File(root, recipe.getProperty("apkPath").substring(1)), apk);
        Config settings = new Config(new Properties());
        settings.set("dst", "true"); settings.set("heaters", "true"); settings.set("driverLevel", "1");
        Files.store(new File(state, "config.properties"), settings.values);
        PatchManager.SystemOps readOnly = new PatchManager.SystemOps() {
            @Override public File file(String path) { return new File(root, path.substring(1)); }
            @Override public void writable() { throw new AssertionError("Migration attempted a remount"); }
            @Override public void finish() { }
            @Override public java.util.List<String> layouts() { return java.util.Collections.emptyList(); }
            @Override public void replace(File f, byte[] b) { throw new AssertionError("Migration attempted a system write"); }
        };
        byte[] bad = stock.clone(); bad[50] ^= 1;
        try { LegacyNavMigration.adopt(state, recipes, bad, readOnly, "test"); throw new AssertionError("Accepted wrong stock"); }
        catch (IOException expected) { }
        byte[] altered = current.clone(); altered[60] ^= 1; Files.write(target, altered);
        try { LegacyNavMigration.adopt(state, recipes, stock, readOnly, "test"); throw new AssertionError("Accepted foreign ODEX"); }
        catch (IOException expected) { }
        Files.write(target, current);
        Files.write(new File(root, recipe.getProperty("apkPath").substring(1)), new byte[]{5});
        try { LegacyNavMigration.adopt(state, recipes, stock, readOnly, "test"); throw new AssertionError("Accepted foreign APK"); }
        catch (IOException expected) { }
        Files.write(new File(root, recipe.getProperty("apkPath").substring(1)), apk);
        LegacyNavMigration.adopt(state, recipes, stock, readOnly, "test");
        LegacyNavMigration.adopt(state, recipes, stock, readOnly, "test"); // repeat safely
        Config adopted = new Config(Files.properties(new File(state, "config.properties")));
        if (!adopted.enabled("nav") || adopted.enabled("popover") || !adopted.enabled("dst")
            || !adopted.enabled("heaters") || adopted.number("driverLevel") != 1) throw new AssertionError("Lost preferences");
        if (!Arrays.equals(current, Files.read(target))) throw new AssertionError("Changed active ODEX");
        final int[] writes = {0};
        PatchManager manager = new PatchManager(state, recipes, new PatchManager.SystemOps() {
            @Override public File file(String path) { return new File(root, path.substring(1)); }
            @Override public void writable() { }
            @Override public void finish() { }
            @Override public java.util.List<String> layouts() { return java.util.Collections.emptyList(); }
            @Override public void replace(File f, byte[] b) throws Exception { writes[0]++; Files.write(f, b); }
        });
        manager.check("nav", adopted); manager.check("popover", adopted);
        adopted.set("popover", "true"); manager.apply(adopted, "test");
        if (!Arrays.equals(PatchManager.transform(recipe, stock, adopted), Files.read(target))) throw new AssertionError("Composition failed");
        adopted.set("nav", "false"); manager.apply(adopted, "test");
        if (!Arrays.equals(PatchManager.transform(recipe, stock, adopted), Files.read(target))) throw new AssertionError("Lost pop-over when disabling handle");
        adopted.set("popover", "false"); manager.apply(adopted, "test");
        if (!Arrays.equals(stock, Files.read(target))) throw new AssertionError("Did not restore stock");
        System.out.println("PASS: rejects wrong stock/ODEX/APK; idempotent adoption preserves preferences and active bytes; both panel controls compose and restore stock.");
    }
}
