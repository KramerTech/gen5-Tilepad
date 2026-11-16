package com.tilepad.longstar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class StarHoldTest {
    /**
     * The car's mode service, the timer and the mark, with a clock the test moves on. Everything
     * the hold does is recorded in order, with the time: "800 disable", "2500 enable", "800 open".
     */
    private static final class Car implements StarHold.Host {
        String action = "com.mobis.intent.action.HOME";
        boolean actionFails, disableFails, enableFails, markThere;
        long now;
        final List<String> did = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        final List<Object[]> tasks = new ArrayList<>();

        @Override public String action() throws Exception {
            if (actionFails) throw new java.io.IOException("not supported");
            return action;
        }
        @Override public void disable(String[] actions) throws Exception {
            if (disableFails) throw new java.io.IOException("refused");
            did.add(now + " disable " + Arrays.toString(actions));
        }
        @Override public void enable() throws Exception {
            if (enableFails) throw new java.io.IOException("refused");
            did.add(now + " enable");
        }
        @Override public StarHold.Task after(long ms, Runnable r) {
            final Object[] task = {now + ms, r};
            tasks.add(task);
            return new StarHold.Task() {
                @Override public void cancel() { tasks.remove(task); }
            };
        }
        @Override public boolean marked() { return markThere; }
        @Override public void mark(boolean blocked) { markThere = blocked; }
        @Override public void open() { did.add(now + " open"); }
        @Override public long now() { return now; }
        @Override public void log(String line) { log.add(line); }

        /** Runs the timer's tasks due up to t, in time order, then moves the clock to t. */
        void until(long t) {
            while (true) {
                Object[] next = null;
                for (Object[] task : tasks) if ((Long) task[0] <= t && (next == null || (Long) task[0] < (Long) next[0])) next = task;
                if (next == null) break;
                tasks.remove(next);
                now = (Long) next[0];
                ((Runnable) next[1]).run();
            }
            now = t;
        }
    }

    private final Car car = new Car();
    private final StarHold hold = new StarHold(car, 800);

    private void key(long t, int value) { car.until(t); hold.onKey(value); }

    @Test public void aCustomKeyHoldBlocksWithTheCustomKeysOwnNone() {
        // A Sonata's programmable key (SHORTCUT_CUSTOM) has its own None action, which isn't blocked
        // as an action and counts as nothing to block.
        Car sonata = new Car();
        StarHold custom = new StarHold(sonata, 800, StarHold.CUSTOM_NONE_ACTION);
        sonata.until(0); custom.onKey(1); sonata.until(800);
        assertEquals(Arrays.asList("800 disable [com.mobis.intent.action.HOME, " + StarHold.CUSTOM_NONE_ACTION + "]", "800 open"), sonata.did);
        Car none = new Car(); none.action = StarHold.CUSTOM_NONE_ACTION;
        StarHold unset = new StarHold(none, 800, StarHold.CUSTOM_NONE_ACTION);
        none.until(0); unset.onKey(1); none.until(800);
        assertEquals(Arrays.asList("800 open"), none.did);
    }

    @Test public void aHoldBlocksTheCarsActionOpensAtOnceAndLiftsItAfterTheRelease() {
        key(0, 1);
        car.until(800);
        assertEquals(Arrays.asList("800 disable [com.mobis.intent.action.HOME, " + StarHold.NONE_ACTION + "]", "800 open"), car.did);
        assertTrue("its mark is there while it's blocked", car.markThere);
        key(3000, 0);
        car.until(3499);
        assertEquals("the block outlasts the car's action on the release", 2, car.did.size());
        car.until(3500);
        assertEquals("3500 enable", car.did.get(2));
        assertEquals(3, car.did.size());
        assertTrue(!car.markThere);
        car.until(200000);
        assertEquals("the 2-minute limit finds nothing left to lift", 3, car.did.size());
    }

    @Test public void withStarSetToNoneNothingIsBlockedAndTilepadOpensAtTheHold() {
        for (String none : new String[]{StarHold.NONE_ACTION, "", null}) {
            Car car = new Car();
            car.action = none;
            StarHold hold = new StarHold(car, 800);
            hold.onKey(1);
            car.until(800);
            assertEquals(Arrays.asList("800 open"), car.did);
            car.until(2000);
            hold.onKey(0);
            car.until(200000);
            assertEquals("not opened again on the release, and nothing to lift", Arrays.asList("800 open"), car.did);
            assertTrue(!car.markThere);
        }
    }

    @Test public void whenTheBlockCantBeMadeTilepadOpensAfterTheRelease() {
        for (int way = 0; way < 2; way++) {
            Car car = new Car();
            car.actionFails = way == 0;
            car.disableFails = way == 1;
            StarHold hold = new StarHold(car, 800);
            hold.onKey(1);
            car.until(800);
            assertTrue("nothing opens at the hold", car.did.isEmpty());
            car.until(2000);
            hold.onKey(0);
            car.until(2249);
            assertTrue(car.did.isEmpty());
            car.until(2250);
            assertEquals("after the car's own action", Arrays.asList("2250 open"), car.did);
            car.until(200000);
            assertEquals("nothing to lift", 1, car.did.size());
            assertTrue(!car.markThere);
        }
    }

    @Test public void theNextPressLiftsTheBlockAtOnce() {
        key(0, 1);
        car.until(800);
        key(1000, 0);
        key(1200, 1);
        assertEquals("a short press right after a hold runs the car's action", "1200 enable", car.did.get(2));
        key(1300, 0);
        car.until(200000);
        assertEquals("lifted once", 3, car.did.size());
    }

    @Test public void aBlockWhoseReleaseIsNeverSeenGoesAfterTwoMinutes() {
        key(0, 1);
        car.until(800);
        car.until(800 + StarHold.MAX_BLOCK_MS - 1);
        assertEquals(2, car.did.size());
        car.until(800 + StarHold.MAX_BLOCK_MS);
        assertEquals((800 + StarHold.MAX_BLOCK_MS) + " enable", car.did.get(2));
        key(130000, 0);
        car.until(200000);
        assertEquals("the late release lifts nothing more", 3, car.did.size());
    }

    @Test public void aShortPressIsNeverTouched() {
        key(0, 1);
        key(300, 0);
        car.until(10000);
        assertTrue(car.did.isEmpty());
        assertTrue("the action isn't even read", car.log.isEmpty());
    }

    @Test public void losingTheDeviceLiftsTheBlock() {
        key(0, 1);
        car.until(800);
        car.until(900);
        hold.deviceLost();
        assertEquals("900 enable", car.did.get(2));
        car.until(200000);
        assertEquals(3, car.did.size());
    }

    @Test public void atStartOnlyABlockThisDaemonLeftIsLifted() {
        hold.start();
        assertTrue("no mark: a block from the car's own apps is theirs", car.did.isEmpty());
        car.markThere = true;
        hold.start();
        assertEquals(Arrays.asList("0 enable"), car.did);
        assertTrue(!car.markThere);
    }

    @Test public void aBlockThatCantBeLiftedSaysSo() {
        key(0, 1);
        car.until(800);
        car.enableFails = true;
        key(1000, 0);
        car.until(1500);
        assertTrue(car.log.get(car.log.size() - 1).startsWith("couldn't allow the car's star action again (release)"));
        assertTrue("the mark goes either way", !car.markThere);
    }

    @Test public void aHoldInsideTheCooldownDoesNothing() {
        key(0, 1);
        car.until(800);
        key(900, 0);
        key(1000, 1);
        car.until(1800);
        key(2000, 0);
        car.until(200000);
        assertEquals("one block and one open; the second hold doesn't qualify",
            Arrays.asList("800 disable [com.mobis.intent.action.HOME, " + StarHold.NONE_ACTION + "]", "800 open", "1000 enable"), car.did);
    }
}
