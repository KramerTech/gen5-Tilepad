package com.tilepad.tweakcore;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class TransactionOptionsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File state;
    private Transaction transaction;
    private int patchCalls;
    @Before public void setup() throws Exception {
        state = temp.newFolder();
        transaction = new Transaction(state, new Transaction.Apply() {
            @Override public void run(Config next, Config previous, boolean rollback) { patchCalls++; }
        });
        Config initial = new Config(new Properties());
        initial.set("nav", "true"); initial.set("popover", "true"); initial.set("dst", "true");
        initial.set("heaters", "true"); initial.set("region", "eu"); initial.set("threshold", "100");
        Files.store(new File(state, "config.properties"), initial.values);
    }
    @Test public void savesOptionsWithoutPatchesAndPreservesOtherSettings() throws Exception {
        Config saved = transaction.updateHeaterOptions("threshold", "106", "driverLevel", "1", "units", "F", "passenger", "true");
        assertEquals(0, patchCalls);
        assertEquals(106, saved.number("threshold")); assertEquals(1, saved.number("driverLevel")); assertTrue(saved.checked("passenger"));
        Config loaded = transaction.load(); assertEquals(saved.values, loaded.values);
        assertTrue(loaded.enabled("nav")); assertTrue(loaded.enabled("popover")); assertTrue(loaded.enabled("dst")); assertEquals("eu", loaded.get("region"));
        assertFalse(new File(state, "pending.properties").exists());
    }
    @Test public void cannotUseOptionsPathForAnyMasterSwitchOrDstRegion() throws Exception {
        Properties before = transaction.load().values;
        for (String key : new String[]{"dst", "heaters", "consent", "tracks", "nav", "popover", "region", "unknown"}) {
            try { transaction.updateHeaterOptions("threshold", "106", key, "true"); fail(key); } catch (IllegalArgumentException expected) { }
            assertEquals(before, transaction.load().values);
        }
        assertEquals(0, patchCalls);
    }
    @Test public void invalidOptionsNeverPartiallyCommit() throws Exception {
        Properties before = transaction.load().values;
        for (String[] pairs : new String[][]{{"threshold", "106", "driverLevel", "4"}, {"threshold", "601"}, {"units", "K"}, {"driver", "yes"}, {"threshold"}, {}}) {
            try { transaction.updateHeaterOptions(pairs); fail(); } catch (IllegalArgumentException expected) { }
            assertEquals(before, transaction.load().values);
        }
    }
    @Test public void pendingPatchTransactionMustFinishBeforeOptionSave() throws Exception {
        Properties pending = (Properties) transaction.load().values.clone(); pending.setProperty("consent", "true");
        Files.store(new File(state, "pending.properties"), pending);
        try { transaction.updateHeaterOptions("threshold", "106"); fail(); } catch (IOException expected) { }
        assertEquals(100, transaction.load().number("threshold")); assertEquals(0, patchCalls);
        transaction.recover(); assertEquals(1, patchCalls);
        transaction.updateHeaterOptions("threshold", "106");
        assertTrue(transaction.load().enabled("consent")); assertEquals(1, patchCalls);
    }
}
