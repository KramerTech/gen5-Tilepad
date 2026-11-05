package com.tilepad.tweakcore;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class TransactionPatchTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File state, root, recipes;
    private Properties right;
    private PatchManager manager;
    private FakeSystem ops;
    private final byte[] original = new byte[160];
    private static final String KEY = "/system/usr/keylayout/keys.kl";
    private static final byte[] KEYS = ("# keep comments\nkey 17 TUNE\nkey 34    CHANNEL_UP\nkey 35    CHANNEL_DOWN\nkey 97 CHANNEL_UP\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @Before public void setup() throws Exception {
        state = temp.newFolder("state"); root = temp.newFolder("root"); recipes = temp.newFolder("recipes");
        for (int i = 0; i < original.length; i++) original[i] = (byte) i;
        // Embedded optimized DEX checksum is valid even with no enabled operations.
        java.util.zip.Adler32 sum = new java.util.zip.Adler32(); sum.update(original, 52, 108);
        for (int i = 0; i < 4; i++) original[48 + i] = (byte) (sum.getValue() >>> (8 * i));
        ops = new FakeSystem();
        right = recipe("/system/app/RightScreenHome.odex");
        right.setProperty("nav", "80,50,01"); right.setProperty("popover", "90,5a,02");
        Files.store(new File(recipes, "right.properties"), right);
        Properties consent = recipe("/system/priv-app/SystemUI.odex"); consent.setProperty("consent", "80,50,03"); Files.store(new File(recipes, "consent.properties"), consent);
        for (int i = 0; i < 2; i++) {
            Properties p = new Properties(); p.setProperty("path", i == 0 ? KEY : KEY + "2"); p.setProperty("kind", "tracks"); p.setProperty("stockSha256", Files.hash(KEYS));
            Files.write(ops.file(p.getProperty("path")), KEYS); Files.store(new File(recipes, "tracks" + i + ".properties"), p);
        }
        manager = new PatchManager(state, recipes, ops);
    }
    private Properties recipe(String path) throws Exception {
        Properties p = new Properties(); p.setProperty("path", path); p.setProperty("apkPath", path + ".apk");
        p.setProperty("stockSha256", Files.hash(original)); p.setProperty("apkSha256", Files.hash(new byte[]{5}));
        p.setProperty("dexOffset", "40"); p.setProperty("dexSize", "120");
        Files.write(ops.file(path), original); Files.write(ops.file(path + ".apk"), new byte[]{5}); return p;
    }
    private static Config config(String... ids) { Config c = new Config(new Properties()); for (String id : ids) c.set(id, "true"); return c; }
    private Transaction transaction() { return new Transaction(state, new Transaction.Apply() { @Override public void run(Config c, Config previous, boolean rollback) throws Exception { manager.apply(c, previous, "boot-1"); } }); }
    @Test public void rightPanelOptionsComposeAndRestoreInEitherOrder() throws Exception {
        for (String[] sequence : new String[][]{{"nav", "popover"}, {"popover", "nav"}}) {
            manager.apply(config(sequence[0]), "boot-1");
            manager.apply(config(sequence), "boot-1");
            assertArrayEquals(PatchManager.transform(right, original, config("nav", "popover")), Files.read(ops.file(right.getProperty("path"))));
            manager.apply(config(sequence[1]), "boot-1");
            assertArrayEquals(PatchManager.transform(right, original, config(sequence[1])), Files.read(ops.file(right.getProperty("path"))));
            manager.apply(config(), "boot-1");
            assertArrayEquals(original, Files.read(ops.file(right.getProperty("path"))));
        }
        assertArrayEquals(original, Files.read(new File(state, "files/RightScreenHome.odex/stock")));
    }
    @Test public void repeatedEnableIsIdempotentAndTrackEditPreservesOtherKeys() throws Exception {
        Config c = config("tracks"); manager.apply(c, "boot-1"); int count = ops.writes;
        manager.apply(c, "boot-1"); assertEquals(count, ops.writes);
        String result = new String(Files.read(ops.file(KEY)), "UTF-8");
        assertTrue(result.contains("key 34    CHANNEL_DOWN\nkey 35    CHANNEL_UP"));
        assertTrue(result.contains("key 97 CHANNEL_UP")); assertTrue(result.contains("key 17 TUNE"));
        manager.apply(config(), "boot-1"); assertArrayEquals(KEYS, Files.read(ops.file(KEY)));
    }
    @Test public void allAffectedFilesValidateBeforeAnySystemWrite() throws Exception {
        Files.write(ops.file(KEY), new byte[]{9});
        try { manager.apply(config("consent", "tracks"), "boot-1"); fail(); } catch (IOException expected) { }
        assertEquals(0, ops.writes); assertArrayEquals(original, Files.read(ops.file("/system/priv-app/SystemUI.odex")));
    }
    @Test public void pairedApkMismatchRejectsEvenAStockOdex() throws Exception {
        Files.write(ops.file(right.getProperty("apkPath")), new byte[]{7});
        try { manager.apply(config("nav"), "boot-1"); fail(); } catch (IOException expected) { }
        assertEquals(0, ops.writes);
    }
    @Test public void laterForeignChangesAreNeverOverwritten() throws Exception {
        manager.apply(config("nav"), "boot-1");
        Files.write(ops.file(right.getProperty("path")), new byte[]{55}); int count = ops.writes;
        try { manager.apply(config(), "boot-1"); fail(); } catch (IOException expected) { }
        assertEquals(count, ops.writes); assertArrayEquals(new byte[]{55}, Files.read(ops.file(right.getProperty("path"))));
        assertTrue(new File(state, "files/RightScreenHome.odex/stock").isFile());
    }
    @Test public void writeFailureRollsBackEveryChangedFileAndKeepsOldConfig() throws Exception {
        ops.failWrite = 2;
        try { transaction().commit(config("consent", "nav")); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("Previous settings")); }
        assertFalse(transaction().load().enabled("nav")); assertFalse(new File(state, "pending.properties").exists());
        assertArrayEquals(original, Files.read(ops.file("/system/priv-app/SystemUI.odex")));
        assertArrayEquals(original, Files.read(ops.file(right.getProperty("path"))));
    }
    @Test public void processDeathAfterReplacementResumesFromWriteAheadJournal() throws Exception {
        ops.crashWrite = 1;
        try { transaction().commit(config("consent", "nav")); fail(); } catch (SimulatedDeath expected) { }
        assertTrue(new File(state, "pending.properties").isFile());
        ops.crashWrite = -1; transaction().recover();
        assertTrue(transaction().load().enabled("consent")); assertTrue(transaction().load().enabled("nav"));
        assertFalse(new File(state, "pending.properties").exists());
        transaction().commit(config());
        assertArrayEquals(original, Files.read(ops.file(right.getProperty("path"))));
    }
    @Test public void failedEnableLeavesControlsUsableAfterSuccessfulRollback() throws Exception {
        Files.write(ops.file(KEY), new byte[]{99});
        try { transaction().commit(config("tracks")); fail(); } catch (IOException expected) { }
        assertFalse(new File(state, "pending.properties").exists());
        transaction().commit(config("nav")); assertTrue(transaction().load().enabled("nav"));
    }
    @Test public void changedSwitchDoesNotReadUnrelatedOwnedSystemFiles() throws Exception {
        Config before = config("nav", "popover", "tracks"); transaction().commit(before);
        assertTrue(ops.file(right.getProperty("apkPath")).delete());
        assertTrue(ops.file(right.getProperty("path")).delete());
        Config next = config("nav", "popover", "tracks", "consent"); transaction().commit(next);
        assertTrue(transaction().load().enabled("consent"));
        assertFalse(ops.file(right.getProperty("path")).exists());
    }
    @Test public void runtimeOptionsDoNotOpenAnyPatchFilesOrJournal() throws Exception {
        Config before = config("nav", "dst", "heaters"); transaction().commit(before);
        int writes = ops.writes;
        assertTrue(ops.file(right.getProperty("apkPath")).delete());
        Config next = new Config((Properties) before.values.clone()); next.set("threshold", "106"); next.set("region", "eu"); next.set("heaters", "false");
        transaction().commit(next);
        assertEquals(writes, ops.writes); assertEquals(106, transaction().load().number("threshold"));
        assertFalse(new File(state, "pending.properties").exists());
    }
    @Test public void recoveryRetainsScopeAfterAProcessDeath() throws Exception {
        transaction().commit(config("consent"));
        ops.crashWrite = ops.writes + 1;
        try { transaction().commit(config("consent", "nav")); fail(); } catch (SimulatedDeath expected) { }
        assertTrue(ops.file("/system/priv-app/SystemUI.odex.apk").delete());
        ops.crashWrite = -1; transaction().recover();
        assertTrue(transaction().load().enabled("nav"));
        assertNull(transaction().load().values.getProperty("_tilepad.delta"));
    }
    @Test public void fullRestoreAuditsOwnedFilesEvenWhenConfigAlreadySaysOff() throws Exception {
        manager.apply(config("nav"), "boot-1");
        assertFalse(transaction().load().enabled("nav"));
        transaction().commitFull(config());
        assertArrayEquals(original, Files.read(ops.file(right.getProperty("path"))));
    }
    @Test public void repeatedSelectionDoesNotRewriteConfigOrAuditPairedApk() throws Exception {
        Config before = config("nav"); transaction().commit(before);
        byte[] saved = Files.read(new File(state, "config.properties")); int writes = ops.writes;
        assertTrue(ops.file(right.getProperty("apkPath")).delete()); transaction().commit(before);
        assertEquals(writes, ops.writes); assertArrayEquals(saved, Files.read(new File(state, "config.properties")));
    }
    @Test public void scopedPanelChangesStillPreserveTheOtherPanelSwitch() throws Exception {
        transaction().commit(config("nav")); transaction().commit(config("nav", "popover")); transaction().commit(config("popover"));
        assertArrayEquals(PatchManager.transform(right, original, config("popover")), Files.read(ops.file(right.getProperty("path"))));
        transaction().commit(config()); assertArrayEquals(original, Files.read(ops.file(right.getProperty("path"))));
    }
    private static final class SimulatedDeath extends Error { }
    private final class FakeSystem implements PatchManager.SystemOps {
        int writes, failWrite = -1, crashWrite = -1;
        @Override public File file(String path) { return new File(root, path.substring(1)); }
        @Override public void writable() { }
        @Override public void finish() { }
        @Override public void replace(File target, byte[] bytes) throws Exception {
            writes++; Files.write(target, bytes);
            if (writes == failWrite) throw new IOException("Simulated disk failure after replacement");
            if (writes == crashWrite) throw new SimulatedDeath();
        }
    }
}
