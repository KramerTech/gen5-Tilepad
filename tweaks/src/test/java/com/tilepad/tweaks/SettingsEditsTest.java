package com.tilepad.tweaks;

import com.tilepad.tweakcore.Config;
import java.util.Properties;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsEditsTest {
    private final SettingsEdits edits = new SettingsEdits();
    private Properties server(String[] args) {
        Properties p = edits.confirmed(); Config c = new Config(p);
        for (int i = 1; i < args.length; i += 2) c.set(args[i], args[i + 1]);
        return p;
    }
    @Test public void togglingBackBeforeDispatchIsANoop() {
        edits.edit("nav", "true"); edits.edit("nav", "false");
        assertFalse(edits.hasWork()); assertNull(edits.take());
    }
    @Test public void allControlsShareOneBatch() {
        edits.edit("dst", "true"); edits.edit("region", "eu"); edits.edit("heaters", "true"); edits.edit("threshold", "106"); edits.edit("nav", "true");
        String[] args = edits.take(); assertEquals("update", args[0]); assertEquals(11, args.length);
        Properties saved = server(args); edits.finish(saved);
        assertEquals(saved, edits.display()); assertFalse(edits.hasWork());
    }
    @Test public void slowResponseCannotOverwriteNewerEdits() {
        edits.edit("nav", "true"); Properties response = server(edits.take());
        edits.edit("nav", "false"); edits.edit("threshold", "106");
        assertEquals("false", edits.display().getProperty("nav"));
        edits.finish(response);
        assertEquals("false", edits.display().getProperty("nav"));
        Properties saved = server(edits.take()); edits.finish(saved);
        assertEquals("false", saved.getProperty("nav")); assertEquals("106", saved.getProperty("threshold"));
    }
    @Test public void hundredsOfEditsProduceOneLatestBatchBehindAnActiveWrite() {
        edits.edit("consent", "true"); Properties response = server(edits.take());
        for (int i = 0; i < 500; i++) edits.edit("threshold", Integer.toString(i));
        edits.edit("region", "eu"); edits.finish(response);
        String[] latest = edits.take(); assertEquals(5, latest.length);
        Properties saved = server(latest); edits.finish(saved);
        assertEquals("499", saved.getProperty("threshold")); assertEquals("eu", saved.getProperty("region")); assertNull(edits.take());
    }
    @Test public void rollbackReconcilesFailedSwitchAndRetainsLaterIndependentEdit() {
        edits.edit("popover", "true"); edits.take(); edits.edit("driverLevel", "1");
        edits.finish(new Properties());
        assertFalse(new Config(edits.display()).enabled("popover")); assertEquals("1", edits.display().getProperty("driverLevel"));
        assertEquals(3, edits.take().length);
    }
    @Test public void unknownStateStopsFurtherWrites() {
        edits.edit("nav", "true"); edits.take(); edits.edit("threshold", "106"); edits.finish(null);
        assertFalse(edits.hasWork()); assertFalse(new Config(edits.display()).enabled("nav")); assertNull(edits.take());
    }
    @Test public void invalidEditNeverEntersQueue() {
        try { edits.edit("unknown", "true"); fail(); } catch (IllegalArgumentException expected) { }
        assertFalse(edits.hasWork());
    }
    @Test public void snapshotsCannotMutateQueueState() {
        edits.edit("nav", "true"); edits.display().setProperty("nav", "false");
        assertEquals("true", edits.display().getProperty("nav"));
        try { edits.reset(new Properties()); fail(); } catch (IllegalStateException expected) { }
    }
}
