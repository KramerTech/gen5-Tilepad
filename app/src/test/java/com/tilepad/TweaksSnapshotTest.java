package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class TweaksSnapshotTest {
    @Test public void anyEnabledTweakMeansOn() {
        assertFalse(TweaksSnapshot.of(0, 6).on());
        assertTrue(TweaksSnapshot.of(1, 6).on()); assertTrue(TweaksSnapshot.of(6, 6).on());
        TweaksSnapshot s = TweaksSnapshot.parse("# summary\nenabled=3\ntotal=6\n");
        assertEquals(3, s.enabled); assertEquals(6, s.total); assertNull(s.problem);
    }
    @Test public void invalidOrMissingSummaryDoesNotClaimOff() {
        for (String text : new String[]{"", "enabled=0", "enabled=-1\ntotal=6", "enabled=7\ntotal=6", "enabled=0\ntotal=0", "error"}) {
            TweaksSnapshot s = TweaksSnapshot.parse(text); assertEquals(-1, s.enabled); assertNotNull(s.problem);
        }
        assertEquals(-1, TweaksSnapshot.checking().enabled); assertNull(TweaksSnapshot.checking().problem);
    }
    @Test public void readsWhetherTheNavHandleIsHidden() {
        assertTrue(TweaksSnapshot.parse("enabled=3\ntotal=10\nhandleHidden=true\n").handleHidden);
        assertFalse(TweaksSnapshot.parse("enabled=3\ntotal=10\nhandleHidden=false\n").handleHidden);
        assertFalse(TweaksSnapshot.parse("enabled=3\ntotal=10\n").handleHidden);
    }
}
