package com.tilepad.tweakcore;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class OtaClientTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File state;
    private FakePm pm;
    private OtaClient client;
    private Transaction transaction;
    private boolean dieAfterApply, failAfterApply;
    @Before public void setup() throws Exception {
        state = temp.newFolder(); pm = new FakePm(); client = new OtaClient(state, pm);
        transaction = new Transaction(state, (next, previous, rollback) -> {
            client.apply(next, previous);
            if (dieAfterApply) { dieAfterApply = false; throw new SimulatedDeath(); }
            if (failAfterApply && !rollback) throw new IOException("Another tweak failed");
        });
    }
    private Config wanted(boolean enabled) throws Exception {
        Config c = transaction.load(); c.set("ota", Boolean.toString(enabled)); return c;
    }
    private File record() { return new File(state, "ota-client.disabled"); }
    @Test public void disableAndOffTakeEffectImmediatelyAndVerifyTheExactPackage() throws Exception {
        assertFalse(transaction.load().enabled("ota"));
        transaction.commit(wanted(true));
        assertTrue(pm.disabled); assertTrue(record().isFile()); assertTrue(transaction.load().enabled("ota"));
        assertEquals(1, pm.disables); client.check(transaction.load());
        transaction.commit(wanted(false));
        assertFalse(pm.disabled); assertFalse(record().exists()); assertEquals(1, pm.enables);
    }
    @Test public void freshOffAndFullRestoreDoNotChangeAnUnmanagedClient() throws Exception {
        pm.disabled = true;
        transaction.commitFull(wanted(false));
        assertTrue(pm.disabled); assertEquals(0, pm.enables); assertEquals(0, pm.disables);
    }
    @Test public void optInAlsoOwnsAPreviouslyDisabledClientAndUninstallReenablesIt() throws Exception {
        pm.disabled = true; transaction.commit(wanted(true));
        transaction.commitFull(wanted(false));
        assertFalse(pm.disabled); assertFalse(record().exists());
    }
    @Test public void newRuntimeAndUnrelatedSettingsPreserveDisabledState() throws Exception {
        transaction.commit(wanted(true));
        OtaClient replacement = new OtaClient(state, pm);
        Transaction updated = new Transaction(state, (next, previous, rollback) -> replacement.apply(next, previous));
        updated.recover();
        Config c = updated.load(); c.set("threshold", "120"); updated.commit(c);
        assertTrue(pm.disabled); assertEquals(0, pm.enables); assertEquals(1, pm.disables);
        assertTrue(updated.load().enabled("ota")); assertTrue(record().isFile());
        c.set("ota", "false"); updated.commitFull(c);
        assertFalse(pm.disabled);
    }
    @Test public void interruptedDisableRecoversWithoutLosingRestoration() throws Exception {
        dieAfterApply = true;
        try { transaction.commit(wanted(true)); fail(); } catch (SimulatedDeath expected) { }
        assertTrue(pm.disabled); assertTrue(record().exists());
        assertTrue(new File(state, "pending.properties").exists()); assertFalse(transaction.load().enabled("ota"));
        transaction.recover(); assertTrue(transaction.load().enabled("ota"));
        transaction.commitFull(wanted(false)); assertFalse(pm.disabled);
    }
    @Test public void uninstallSupersedesAnInterruptedEnableIntent() throws Exception {
        dieAfterApply = true;
        try { transaction.commit(wanted(true)); fail(); } catch (SimulatedDeath expected) { }
        transaction.commitFull(wanted(false));
        assertFalse(pm.disabled); assertFalse(record().exists()); assertFalse(new File(state, "pending.properties").exists());
    }
    @Test public void silentPmFailureIsNotSavedAsEnabledAndRollsBack() throws Exception {
        pm.ignoreDisable = true;
        try { transaction.commit(wanted(true)); fail(); } catch (IOException expected) { }
        assertFalse(transaction.load().enabled("ota")); assertFalse(record().exists()); assertFalse(pm.disabled);
    }
    @Test public void missingPackageCannotEnableTheTweakOrLeavePendingState() throws Exception {
        pm.installed = false;
        try { transaction.commit(wanted(true)); fail(); } catch (IOException expected) { }
        assertFalse(transaction.load().enabled("ota")); assertFalse(record().exists());
        assertFalse(new File(state, "pending.properties").exists()); assertEquals(0, pm.enables);
    }
    @Test public void laterTransactionFailureReenablesTheClient() throws Exception {
        failAfterApply = true;
        try { transaction.commit(wanted(true)); fail(); } catch (IOException expected) { }
        assertFalse(pm.disabled); assertFalse(record().exists()); assertFalse(transaction.load().enabled("ota"));
    }
    @Test public void failedRestoreRetainsOwnershipAndCanBeRetried() throws Exception {
        transaction.commit(wanted(true)); pm.ignoreEnable = true;
        try { transaction.commitFull(wanted(false)); fail(); } catch (IOException expected) { }
        assertTrue(record().exists()); assertTrue(pm.disabled); assertTrue(transaction.load().enabled("ota"));
        pm.ignoreEnable = false; transaction.commitFull(wanted(false));
        assertFalse(pm.disabled); assertFalse(record().exists());
    }
    @Test public void auditDetectsAnExternallyReenabledClient() throws Exception {
        transaction.commit(wanted(true)); pm.disabled = false;
        try { client.check(transaction.load()); fail(); } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("off and on"));
        }
    }
    private static final class SimulatedDeath extends Error { }
    private static final class FakePm implements OtaClient.Commands {
        boolean installed = true, disabled, ignoreDisable, ignoreEnable;
        int enables, disables;
        @Override public String run(String... args) {
            assertEquals("pm", args[0]); assertEquals(OtaClient.PACKAGE, args[args.length - 1]);
            if (args[1].equals("path")) return installed ? "package:/system/app/HKMC_DMClient_OTA.apk\n" : "";
            if (args[1].equals("list")) {
                assertEquals("packages", args[2]); assertEquals("--user", args[4]); assertEquals("0", args[5]);
                boolean match = installed && (args[3].equals("-d") ? disabled : !disabled);
                // Similar package names must never count as a successful read-back.
                return "package:" + OtaClient.PACKAGE + ".other\n" + (match ? "package:" + OtaClient.PACKAGE + "\n" : "");
            }
            assertEquals("--user", args[2]); assertEquals("0", args[3]);
            if (args[1].equals("disable")) { disables++; if (!ignoreDisable && installed) disabled = true; }
            else { assertEquals("enable", args[1]); enables++; if (!ignoreEnable && installed) disabled = false; }
            return "";
        }
    }
}
