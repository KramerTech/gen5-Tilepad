package com.tilepad.tweakcore;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.tilepad.tweakcore.ClimatePolicy.*;

public class ClimatePolicyTest {
    private static Climate sync(float dr, float ps) { return new Climate(true, true, false, dr, ps); }
    private static Climate driverOnly(float dr, float ps) { return new Climate(true, false, true, dr, ps); }
    private static Climate unsynced(float dr, float ps) { return new Climate(true, false, false, dr, ps); }

    // Sequences recorded on the car on 2026-11-04 (dumpsys + DATCService log).
    @Test public void passengerUpMovesTheDriverStepByStepThenResyncs() {
        AlwaysSync a = new AlwaysSync();
        assertEquals(NONE, feed(a, sync(23.5f, 23.5f)));
        assertEquals(NONE, feed(a, sync(23.5f, 24.0f)));          // temperature reported before sync drops
        assertEquals(DRIVER_UP, feed(a, unsynced(23.5f, 24.0f))); a.sent(DRIVER_UP, clock);
        assertEquals(DRIVER_UP, feed(a, unsynced(23.5f, 24.5f)));  // a second press before ours landed
        a.sent(DRIVER_UP, clock);
        assertEquals(DRIVER_UP, feed(a, unsynced(24.0f, 24.5f)));
        assertEquals(SYNC, feed(a, unsynced(24.5f, 24.5f)));
        assertEquals(NONE, feed(a, sync(24.5f, 24.5f)));
    }
    @Test public void passengerDownMovesTheDriverDown() {
        AlwaysSync a = new AlwaysSync();
        feed(a, sync(23.5f, 23.5f));
        assertEquals(DRIVER_DOWN, feed(a, unsynced(23.5f, 23.0f))); a.sent(DRIVER_DOWN, clock);
        assertEquals(SYNC, feed(a, unsynced(23.0f, 23.0f)));
        feed(a, sync(23.0f, 23.0f));
        assertEquals(DRIVER_DOWN, feed(a, unsynced(23.0f, 22.5f)));  // the new setting is the reference now
    }
    /** Feed a reading the way the controller does: observe every one, decide on the latest. */
    private static long clock;
    private static int feed(AlwaysSync a, Climate c) { return feed(a, c, clock += 1000); }
    private static int feed(AlwaysSync a, Climate c, long now) { clock = now; a.observe(c, now); return a.next(c, now); }
    @Test public void aDriverPressDuringTheCatchUpWinsAndStopsIt() {
        AlwaysSync a = new AlwaysSync();
        feed(a, sync(22.0f, 22.0f));
        assertEquals(DRIVER_UP, feed(a, unsynced(22.0f, 23.0f)));  // passenger tapped twice
        a.sent(DRIVER_UP, clock);
        assertEquals(DRIVER_UP, feed(a, unsynced(22.5f, 23.0f)));  // our step arrived
        a.sent(DRIVER_UP, clock);
        assertEquals(SYNC, feed(a, unsynced(22.0f, 23.0f)));       // driver pressed down before ours landed
        assertEquals(SYNC, feed(a, unsynced(22.5f, 23.0f)));       // our late step is accounted for, still Sync
        assertEquals(NONE, feed(a, sync(22.5f, 22.5f)));
        assertEquals(DRIVER_DOWN, feed(a, unsynced(22.5f, 22.0f)));  // the next passenger change is followed again
    }
    @Test public void aDriverPressInTheSameDirectionBeyondOurStepsAlsoWins() {
        AlwaysSync a = new AlwaysSync();
        feed(a, sync(22.0f, 22.0f));
        assertEquals(DRIVER_UP, feed(a, unsynced(22.0f, 22.5f)));
        a.sent(DRIVER_UP, clock);
        assertEquals(SYNC, feed(a, unsynced(22.5f, 22.5f)));  // caught up
        assertEquals(SYNC, feed(a, unsynced(23.0f, 22.5f)));  // then a driver press up: no step of ours pending
        assertEquals(NONE, feed(a, sync(23.0f, 23.0f)));
    }
    @Test public void driverPressesWhileSyncedAreOrdinary() {
        AlwaysSync a = new AlwaysSync();
        feed(a, sync(22.0f, 22.0f));
        assertEquals(NONE, feed(a, sync(22.5f, 22.0f)));  // driver press, passenger follows a moment later
        assertEquals(NONE, feed(a, sync(22.5f, 22.5f)));
        assertEquals(DRIVER_UP, feed(a, unsynced(22.5f, 23.0f)));
    }
    @Test public void fastTapsAreFollowedAtOnceButSyncWaitsForThePassengerToSettle() {
        AlwaysSync a = new AlwaysSync();
        feed(a, sync(22.0f, 22.0f), 0);
        assertEquals(DRIVER_UP, feed(a, unsynced(22.0f, 22.5f), 100));
        a.sent(DRIVER_UP, clock);
        assertEquals(NONE, feed(a, unsynced(22.5f, 22.5f), 150));   // matched, but taps may continue
        assertEquals(DRIVER_UP, feed(a, unsynced(22.5f, 23.0f), 400));
        a.sent(DRIVER_UP, clock);
        assertEquals(NONE, feed(a, unsynced(23.0f, 23.0f), 450));
        assertEquals(NONE, feed(a, unsynced(23.0f, 23.0f), 400 + AlwaysSync.SYNC_QUIET_MS - 1));
        assertEquals(400 + AlwaysSync.SYNC_QUIET_MS, a.syncDueAt());
        assertEquals(SYNC, feed(a, unsynced(23.0f, 23.0f), 400 + AlwaysSync.SYNC_QUIET_MS));
    }
    @Test public void aDriverTapMidBurstResyncsImmediately() {
        AlwaysSync a = new AlwaysSync();
        feed(a, sync(22.0f, 22.0f), 0);
        assertEquals(DRIVER_UP, feed(a, unsynced(22.0f, 22.5f), 100)); a.sent(DRIVER_UP, clock);
        assertEquals(NONE, feed(a, unsynced(22.5f, 22.5f), 150));
        assertEquals(SYNC, feed(a, unsynced(23.0f, 22.5f), 200));  // driver tap 100 ms after the passenger's
    }
    @Test public void upThenBackDownWithOneStepInFlightKeepsFollowingAndWaits() {
        AlwaysSync a = new AlwaysSync();
        feed(a, sync(22.0f, 22.0f), 0);
        assertEquals(DRIVER_UP, feed(a, unsynced(22.0f, 22.5f), 100)); a.sent(DRIVER_UP, 100);
        assertEquals(NONE, feed(a, unsynced(22.0f, 23.0f), 200));       // another press; ours not landed yet
        assertEquals(DRIVER_UP, feed(a, unsynced(22.5f, 23.0f), 250)); a.sent(DRIVER_UP, 250);
        assertEquals(NONE, feed(a, unsynced(22.5f, 22.5f), 300));       // turned back down
        assertEquals(DRIVER_DOWN, feed(a, unsynced(23.0f, 22.5f), 330)); a.sent(DRIVER_DOWN, 330);  // late up step: not a driver press
        assertEquals(NONE, feed(a, unsynced(23.0f, 22.0f), 360));       // back at the start
        assertEquals(DRIVER_DOWN, feed(a, unsynced(22.5f, 22.0f), 390)); a.sent(DRIVER_DOWN, 390);
        assertEquals(NONE, feed(a, unsynced(22.0f, 22.0f), 420));       // matched at the start, still waiting
        assertEquals(360 + AlwaysSync.SYNC_QUIET_MS, a.wakeAt());
        assertEquals(SYNC, feed(a, unsynced(22.0f, 22.0f), 360 + AlwaysSync.SYNC_QUIET_MS));
    }
    @Test public void aLostStepIsRetriedAfterItsDeadline() {
        AlwaysSync a = new AlwaysSync();
        feed(a, sync(22.0f, 22.0f), 0);
        assertEquals(DRIVER_UP, feed(a, unsynced(22.0f, 22.5f), 100)); a.sent(DRIVER_UP, 100);
        assertEquals(NONE, feed(a, unsynced(22.0f, 22.5f), 600));
        assertEquals(100 + AlwaysSync.STEP_WAIT_MS, a.wakeAt());
        assertEquals(DRIVER_UP, feed(a, unsynced(22.0f, 22.5f), 100 + AlwaysSync.STEP_WAIT_MS));
    }
    @Test public void syncTurnedOffByHandPausesUntilSyncIsOnAgain() {
        AlwaysSync a = new AlwaysSync();
        feed(a, sync(22.0f, 22.0f), 0);
        assertEquals(NONE, feed(a, unsynced(22.0f, 22.0f), 5000));  // SYNC pressed off on the screen
        assertTrue(a.paused());
        assertEquals(NONE, feed(a, unsynced(22.0f, 24.0f), 6000));  // passenger set apart on purpose
        assertEquals(NONE, feed(a, unsynced(23.0f, 24.0f), 7000));
        assertEquals(NONE, feed(a, sync(23.0f, 23.0f), 8000));      // SYNC pressed again
        assertFalse(a.paused());
        assertEquals(DRIVER_UP, feed(a, unsynced(23.0f, 23.5f), 9000));  // locked together again
    }
    @Test public void aPassengerPressIsNotTakenForSyncTurnedOff() {
        AlwaysSync a = new AlwaysSync();
        feed(a, sync(22.0f, 22.0f), 0);
        feed(a, sync(22.0f, 22.5f), 5000);
        assertEquals(DRIVER_UP, feed(a, unsynced(22.0f, 22.5f), 5010));
        assertFalse(a.paused());
    }
    @Test public void driverOnlyAndClimateOffAreLeftAlone() {
        AlwaysSync a = new AlwaysSync();
        assertEquals(NONE, feed(a, driverOnly(23.5f, 23.5f)));
        assertEquals(NONE, feed(a, new Climate(false, false, false, 17.0f, 17.0f)));
        assertEquals(DRIVER_UP, feed(a, unsynced(23.5f, 24.0f)));  // a passenger press that leaves Driver Only
    }
    @Test public void unknownStartingPointResyncsWithoutMovingTheDriver() {
        assertEquals(SYNC, feed(new AlwaysSync(), unsynced(22.0f, 21.0f)));
    }
    @Test public void autoDriverOnlyIsOneWayPerBoot() {
        assertEquals(DRIVER_ONLY, autoTarget(STAGE_NEW, false));
        assertEquals(SYNC, autoTarget(STAGE_NEW, true));  // buckled before Driver Only could be set
        assertEquals(STAGE_DRIVER_ONLY, autoStage(STAGE_NEW, DRIVER_ONLY, driverOnly(22f, 22f)));
        assertEquals(STAGE_NEW, autoStage(STAGE_NEW, DRIVER_ONLY, sync(22f, 22f)));
        assertEquals(NONE, autoTarget(STAGE_DRIVER_ONLY, false));  // the user's own changes stay
        assertEquals(SYNC, autoTarget(STAGE_DRIVER_ONLY, true));
        assertEquals(STAGE_SYNC, autoStage(STAGE_DRIVER_ONLY, SYNC, sync(22f, 22f)));
        assertEquals(NONE, autoTarget(STAGE_SYNC, false));  // unbuckling doesn't go back
        assertEquals(NONE, autoTarget(STAGE_SYNC, true));
    }
    @Test public void commandsMatchTheStockClimateApp() {
        assertArrayEquals(new int[][]{{22, 2}}, commands(unsynced(23.5f, 24f), SYNC));
        assertArrayEquals(new int[][]{{24, 0}}, commands(driverOnly(23.5f, 23.5f), SYNC));
        assertArrayEquals(new int[][]{{24, 1}}, commands(sync(23.5f, 23.5f), DRIVER_ONLY));
        assertArrayEquals(new int[][]{{50, 1}}, commands(unsynced(23.5f, 24f), DRIVER_UP));
        assertArrayEquals(new int[][]{{50, 2}}, commands(unsynced(23.5f, 23f), DRIVER_DOWN));
        assertEquals(0, commands(sync(23.5f, 23.5f), SYNC).length);
        assertEquals(0, commands(driverOnly(23.5f, 23.5f), DRIVER_ONLY).length);
        assertEquals(0, commands(sync(23.5f, 23.5f), NONE).length);
    }
    @Test public void parsesTheServiceDump() {
        String dump = "  mPsMode: 15\n  mOpStatus: 1\n  mDual: 1\n  mDriverOnly: 0\n  mSyncDisp: 1\n  mDatcType3\n"
            + "  mDrTempC: 23.5\n  mDrTempF: 75.0\n  mPsTempC: 24.0\n  mPsTempStateC: 65535\n  mOpStatusX: 9\n";
        Climate c = parseDump(dump);
        assertTrue(c.on); assertTrue(c.sync); assertFalse(c.driverOnly);
        assertEquals(23.5f, c.driver, 0); assertEquals(24.0f, c.passenger, 0);
        assertFalse(parseDump(dump.replace("  mOpStatus: 1\n", "  mOpStatus: 2\n")).on);
        assertNull(parseDump(dump.replace("  mSyncDisp: 1\n", "")));
    }
}
