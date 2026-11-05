package com.tilepad.longstar;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HoldDetectorTest {
    private final HoldDetector d = new HoldDetector(800, 1500);

    @Test public void holdFiresOnceWhileStillDown() {
        assertEquals(HoldDetector.ARM, d.onKey(1, 1000));
        int gen = d.generation();
        assertFalse(d.onTimer(gen, 1799));          // early wakeup is ignored
        assertTrue(d.onTimer(gen, 1800));
        assertFalse(d.onTimer(gen, 1900));          // only once per press
        assertEquals(HoldDetector.DISARM, d.onKey(0, 4000));
    }

    @Test public void shortPressNeverFires() {
        // Real capture: TUNE taps on this car last 100-250 ms.
        d.onKey(1, 0);
        int gen = d.generation();
        assertEquals(HoldDetector.DISARM, d.onKey(0, 150));
        assertFalse(d.onTimer(gen, 800));
    }

    @Test public void staleTimerFromEarlierPressCannotFire() {
        d.onKey(1, 0);
        int first = d.generation();
        d.onKey(0, 100);
        d.onKey(1, 200);                            // new press, still held
        assertFalse(d.onTimer(first, 800));         // the first press's timer
        assertTrue(d.onTimer(d.generation(), 1000));
    }

    @Test public void autorepeatDoesNotRestartTheHold() {
        d.onKey(1, 0);
        int gen = d.generation();
        assertEquals(HoldDetector.NONE, d.onKey(2, 500));
        assertEquals(HoldDetector.NONE, d.onKey(1, 600));   // duplicate down
        assertTrue(d.onTimer(gen, 800));
    }

    @Test public void releaseWithoutPressIsIgnored() {
        // e.g. daemon (re)started while the knob was already held
        assertEquals(HoldDetector.NONE, d.onKey(0, 50));
    }

    @Test public void cooldownSuppressesRapidRefire() {
        d.onKey(1, 0);
        assertTrue(d.onTimer(d.generation(), 800));
        d.onKey(0, 900);
        d.onKey(1, 1000);
        assertFalse(d.onTimer(d.generation(), 1800));       // 1000 ms after last fire
        d.onKey(0, 1900);
        d.onKey(1, 2400);
        assertTrue(d.onTimer(d.generation(), 3200));
    }

    @Test public void resetCancelsHoldInProgress() {
        d.onKey(1, 0);
        int gen = d.generation();
        d.reset();
        assertFalse(d.onTimer(gen, 900));
        assertEquals(HoldDetector.ARM, d.onKey(1, 1000));
    }
}
