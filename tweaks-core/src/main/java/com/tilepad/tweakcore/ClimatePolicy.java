package com.tilepad.tweakcore;

import java.util.regex.*;

/**
 * Pure climate decisions for Maintain Climate Sync and Automatic Driver Only; ClimateController feeds it the
 * car's DATC status. Codes are IDATCService.setFrontClimateControl(flag, value) pairs, as the
 * stock climate app sends them (DATCFlag.DATCControl).
 */
public final class ClimatePolicy {
    public static final int NONE = 0, SYNC = 1, DRIVER_ONLY = 2, DRIVER_UP = 3, DRIVER_DOWN = 4;
    public static final int SYNC_FLAG = 22, DRIVER_ONLY_FLAG = 24, DRIVER_STEP_FLAG = 50;
    /** The stock SYNC button sends 2 to turn sync on; the driver up/down buttons send 1/2. */
    public static final int SYNC_ON = 2, STEP_UP = 1, STEP_DOWN = 2;

    /** One DatcStatus reading. Temperatures are °C set points; °F cars step them by the same table. */
    public static final class Climate {
        public final boolean on, sync, driverOnly;
        public final float driver, passenger;
        public Climate(boolean on, boolean sync, boolean driverOnly, float driver, float passenger) {
            this.on = on; this.sync = sync; this.driverOnly = driverOnly; this.driver = driver; this.passenger = passenger;
        }
        /** Neither Sync nor Driver Only: what the DATC shows after a passenger temperature change. */
        public boolean unsynced() { return on && !sync && !driverOnly; }
        public boolean matched() { return Math.abs(driver - passenger) < 0.01f; }
        @Override public String toString() {
            return (on ? "on" : "off") + (sync ? " sync" : "") + (driverOnly ? " driver-only" : "") + " " + driver + "/" + passenger + "°C";
        }
    }

    /**
     * Maintain Climate Sync: the two temperature controls move together. When the passenger set point is
     * changed (the DATC then leaves sync), the driver set point is stepped to it and sync is turned
     * back on once the passenger side has been still for SYNC_QUIET_MS: turning sync on copies the
     * driver set point over the passenger one, so doing it mid-burst would undo a later press.
     * A driver change that isn't one of those steps is the driver's own choice: the
     * catch-up stops and sync is turned on, so the passenger side takes the driver's new setting.
     * Sync turned off by hand (it drops with both sides already matched and no passenger change)
     * pauses all of this until sync is on again. Driver Only is left alone.
     */
    public static final class AlwaysSync {
        public static final long SYNC_QUIET_MS = 700;
        /** One driver step in flight at a time; a step not seen by then is taken as lost. */
        public static final long STEP_WAIT_MS = 1000;
        private long sentAt;
        private Float settledPassenger, lastDriver, lastPassenger;
        private long passengerChangedAt = Long.MIN_VALUE / 2;
        // Our driver steps not yet seen, per direction: after a reversal both can be in flight.
        private int pendingUp, pendingDown;
        // Following: a passenger change is being matched; stays set until sync is back, even if
        // the passenger returns to where it started.
        private boolean driverLead, following, paused, lastOn, lastSync;
        /** Every reading, in arrival order, so each driver change is accounted for. */
        public void observe(Climate c, long now) {
            // With the climate off the set points are placeholders; don't compare across it.
            if (!c.on) { lastDriver = lastPassenger = null; lastOn = false; return; }
            boolean passengerSteady = lastPassenger != null && Math.abs(c.passenger - lastPassenger) < 0.01f
                && now - passengerChangedAt >= SYNC_QUIET_MS;
            if (c.sync) paused = false;
            else if (lastOn && lastSync && c.unsynced() && c.matched() && passengerSteady && !following
                && pendingUp + pendingDown == 0) paused = true;
            lastOn = true; lastSync = c.sync;
            if (lastPassenger != null && Math.abs(c.passenger - lastPassenger) >= 0.01f) passengerChangedAt = now;
            lastPassenger = c.passenger;
            if (lastDriver != null && Math.abs(c.driver - lastDriver) >= 0.01f) {
                boolean up = c.driver > lastDriver;
                if (up && pendingUp > 0) pendingUp--;
                else if (!up && pendingDown > 0) pendingDown--;
                else if (c.unsynced()) driverLead = true;
            }
            lastDriver = c.driver;
        }
        /** Record a request carried out on the car. */
        public void sent(int action, long now) {
            if (action == DRIVER_UP) pendingUp++;
            else if (action == DRIVER_DOWN) pendingDown++;
            sentAt = now;
        }
        private boolean stepInFlight(long now) {
            if (pendingUp + pendingDown == 0) return false;
            if (now - sentAt < STEP_WAIT_MS) return true;
            pendingUp = pendingDown = 0;
            return false;
        }
        /** When to look again while next() is waiting: the step's deadline (its reply wakes us sooner), else the sync quiet period. */
        public long wakeAt() { return pendingUp + pendingDown > 0 ? sentAt + STEP_WAIT_MS : syncDueAt(); }
        /** When a held-back sync becomes due (the passenger side quiet long enough). */
        public long syncDueAt() { return passengerChangedAt + SYNC_QUIET_MS; }
        /** Sync was turned off by hand: nothing is changed until it is on again. */
        public boolean paused() { return paused; }
        public int next(Climate c, long now) {
            if (!c.on || paused) return NONE;
            if (c.sync || c.driverOnly) {
                driverLead = false; following = false; pendingUp = pendingDown = 0;
                // In sync the passenger follows the driver; a mismatch is a press still in flight.
                if (c.driverOnly || c.matched()) settledPassenger = c.passenger;
                return NONE;
            }
            if (driverLead) return SYNC;
            if (settledPassenger != null && Math.abs(c.passenger - settledPassenger) >= 0.01f) following = true;
            if (following && stepInFlight(now)) return NONE;
            if (following && !c.matched()) return c.passenger > c.driver ? DRIVER_UP : DRIVER_DOWN;
            return following && now < syncDueAt() ? NONE : SYNC;
        }
    }

    /** Automatic Driver Only, per drive (car on to car off): Driver Only first, Sync once a passenger buckles up. One way. */
    public static final int STAGE_NEW = 0, STAGE_DRIVER_ONLY = 1, STAGE_SYNC = 2;
    public static int autoTarget(int stage, boolean belted) {
        if (stage >= STAGE_SYNC) return NONE;
        if (belted) return SYNC;
        return stage == STAGE_NEW ? DRIVER_ONLY : NONE;
    }
    /** The stage reached once climate c shows the target. */
    public static int autoStage(int stage, int target, Climate c) {
        if (target == SYNC && c.sync) return STAGE_SYNC;
        if (target == DRIVER_ONLY && c.driverOnly) return STAGE_DRIVER_ONLY;
        return stage;
    }

    /** setFrontClimateControl calls, in order, that carry out action on climate c. */
    public static int[][] commands(Climate c, int action) {
        switch (action) {
            case DRIVER_ONLY: return c.driverOnly ? new int[0][] : new int[][]{{DRIVER_ONLY_FLAG, 1}};
            // Leaving Driver Only restores sync on its own on this car; the sync request covers the rest.
            case SYNC: return c.sync ? new int[0][] : c.driverOnly ? new int[][]{{DRIVER_ONLY_FLAG, 0}} : new int[][]{{SYNC_FLAG, SYNC_ON}};
            case DRIVER_UP: return new int[][]{{DRIVER_STEP_FLAG, STEP_UP}};
            case DRIVER_DOWN: return new int[][]{{DRIVER_STEP_FLAG, STEP_DOWN}};
            default: return new int[0][];
        }
    }

    /** The same fields from `dumpsys com.hkmc.micom.datc`, for the state before the first callback. */
    public static Climate parseDump(String dump) {
        Integer op = integer(dump, "mOpStatus"), sync = integer(dump, "mSyncDisp"), only = integer(dump, "mDriverOnly");
        Float driver = decimal(dump, "mDrTempC"), passenger = decimal(dump, "mPsTempC");
        if (op == null || sync == null || only == null || driver == null || passenger == null) return null;
        return new Climate(op == 1, sync == 1, only == 1, driver, passenger);
    }
    private static Integer integer(String dump, String field) {
        Matcher m = Pattern.compile("(?m)^\\s*" + field + ":\\s*(-?\\d+)\\s*$").matcher(dump);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }
    private static Float decimal(String dump, String field) {
        Matcher m = Pattern.compile("(?m)^\\s*" + field + ":\\s*(-?\\d+(?:\\.\\d+)?)\\s*$").matcher(dump);
        return m.find() ? Float.parseFloat(m.group(1)) : null;
    }
    private ClimatePolicy() { }
}
