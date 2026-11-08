package com.tilepad.tweakcore;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TravelTweaksTest {
    private static final int[] SCHEDULE = {22, 3, 6, 1, 31, 7, 31, 7, 3, 11, 12, 13};
    @Test public void gpsParserUsesGpsNotPassiveOrOtherLocations() throws Exception {
        VehicleLocation f = VehicleLocation.parse("Last Known Locations:\n passive: Location[gps 88,99 acc=1 et=+1s]\n gps: Location[gps 35.1,-78.2 acc=3 accH=4 gpsstatus=3 t=123 et=+1d2h3m4s5ms alt=90]\n");
        assertEquals(35.1, f.latitude, .00001); assertEquals(-78.2, f.longitude, .00001);
        assertEquals(93784005L, f.elapsedMs); assertEquals(3, f.accuracy, 0);
    }
    @Test public void sdkLocationFormatWorksWithoutOemExtras() throws Exception {
        VehicleLocation f = VehicleLocation.parse("  gps: Location[gps 0.0,179.0 acc=20 et=+1m2s3ms]\n");
        assertTrue(f.usable(62004)); assertEquals(62003, f.elapsedMs);
    }
    @Test public void missingOrMalformedLocationCannotBecomeHome() {
        for (String dump : new String[]{"", "gps: null", "passive: Location[gps 1,2 acc=3 et=+1s]", "gps: Location[gps 1,2 acc=??? et=+1s]", "gps: Location[gps 1,2 acc=3 et=?!?]", "gps: Location[gps 1,2 acc=3 et=+1y]"}) {
            try { VehicleLocation.parse(dump); fail(dump); } catch (Exception expected) { }
        }
    }
    @Test public void staleFutureInaccurateAndInvalidCoordinatesAreRejected() {
        assertTrue(new VehicleLocation(35, -78, 150, 1000).usable(61000));
        assertFalse(new VehicleLocation(35, -78, 3, 1000).usable(61001));
        assertFalse(new VehicleLocation(35, -78, 3, 1001).usable(1000));
        assertFalse(new VehicleLocation(35, -78, 151, 1000).usable(1000));
        assertFalse(new VehicleLocation(91, -78, 3, 1000).usable(1000));
        assertFalse(new VehicleLocation(35, Double.NaN, 3, 1000).usable(1000));
        assertFalse(new VehicleLocation(35, -78, Double.POSITIVE_INFINITY, 1000).usable(1000));
    }
    @Test public void hysteresisHonoursStrictAwayAndInclusiveHomeBoundaries() {
        double mile = ChargeAwayPolicy.MILE_M;
        assertEquals(ChargeAwayPolicy.Zone.HOME, ChargeAwayPolicy.zone(ChargeAwayPolicy.Zone.HOME, 1.5*mile, 0));
        assertEquals(ChargeAwayPolicy.Zone.AWAY, ChargeAwayPolicy.zone(ChargeAwayPolicy.Zone.HOME, 1.5*mile+.01, 0));
        assertEquals(ChargeAwayPolicy.Zone.AWAY, ChargeAwayPolicy.zone(ChargeAwayPolicy.Zone.AWAY, mile+.01, 0));
        assertEquals(ChargeAwayPolicy.Zone.HOME, ChargeAwayPolicy.zone(ChargeAwayPolicy.Zone.AWAY, mile, 0));
        assertEquals(ChargeAwayPolicy.Zone.UNKNOWN, ChargeAwayPolicy.zone(ChargeAwayPolicy.Zone.UNKNOWN, 1.2*mile, 0));
    }
    @Test public void accuracyCircleMustCrossTheBoundary() {
        double mile = ChargeAwayPolicy.MILE_M;
        assertEquals(ChargeAwayPolicy.Zone.HOME, ChargeAwayPolicy.zone(ChargeAwayPolicy.Zone.HOME, 1.5*mile+100, 150));
        assertEquals(ChargeAwayPolicy.Zone.AWAY, ChargeAwayPolicy.zone(ChargeAwayPolicy.Zone.AWAY, mile-100, 150));
        assertEquals(ChargeAwayPolicy.Zone.AWAY, ChargeAwayPolicy.zone(ChargeAwayPolicy.Zone.HOME, 1.5*mile+151, 150));
        assertEquals(ChargeAwayPolicy.Zone.HOME, ChargeAwayPolicy.zone(ChargeAwayPolicy.Zone.AWAY, mile-151, 150));
    }
    @Test public void distanceHandlesZeroPolesAndTheDateLine() {
        assertEquals(0, ChargeAwayPolicy.distance(35, -78, 35, -78), .0001);
        assertEquals(22239, ChargeAwayPolicy.distance(0, 179.9, 0, -179.9), 10);
        assertTrue(ChargeAwayPolicy.distance(90, 0, 89.9, 180) > 11000);
    }
    @Test public void awayChangesOnlyTheMasterModeAndDropsOnlyFeedbackFields() {
        int[] request = ChargeAwayPolicy.request(SCHEDULE, ChargeAwayPolicy.mode(ChargeAwayPolicy.Zone.AWAY, SCHEDULE, -1));
        assertEquals(9, request.length); assertEquals(0, request[8]);
        for (int i=0;i<8;i++) assertEquals(SCHEDULE[i], request[i]);
        assertEquals(3, SCHEDULE[8]);
    }
    @Test public void returnHomeRestoresTheStockChoiceWithoutForcingOffPeakOnly() {
        int[] off = SCHEDULE.clone(); off[8]=0;
        assertEquals(3, ChargeAwayPolicy.mode(ChargeAwayPolicy.Zone.HOME, off, 1));
        assertEquals(2, ChargeAwayPolicy.mode(ChargeAwayPolicy.Zone.HOME, off, 0));
        assertEquals(3, ChargeAwayPolicy.mode(ChargeAwayPolicy.Zone.HOME, SCHEDULE, 0));
        assertEquals(0, ChargeAwayPolicy.mode(ChargeAwayPolicy.Zone.UNKNOWN, off, 1));
    }
    @Test public void unknownModeUnconfiguredTimesAndUnreadablePreferenceNeverEnable() {
        int[] off=SCHEDULE.clone();off[8]=0;
        try { ChargeAwayPolicy.mode(ChargeAwayPolicy.Zone.HOME,off,-1);fail(); } catch(IllegalArgumentException expected) { }
        off[0]=31;
        try { ChargeAwayPolicy.mode(ChargeAwayPolicy.Zone.HOME,off,1);fail(); } catch(IllegalArgumentException expected) { }
        assertEquals(0,ChargeAwayPolicy.mode(ChargeAwayPolicy.Zone.AWAY,off,-1));
        off[8]=255;
        try { ChargeAwayPolicy.validate(off);fail(); } catch(IllegalArgumentException expected) { }
        try { ChargeAwayPolicy.validate(new int[9]);fail(); } catch(IllegalArgumentException expected) { }
    }
    @Test public void homeCoordinatesAreValidatedAndBothParticipateInConfigChanges() {
        Config c=new Config(new Properties()), old=new Config(new Properties());
        c.set("homeLat","35.1");
        try {c.validateHome();fail();}catch(IllegalArgumentException expected) { }
        c.set("homeLon","-78.2");c.validateHome();assertTrue(c.hasHome());assertFalse(c.sameSettings(old));
        old.set("homeLat","35.1");old.set("homeLon","-78.2");assertTrue(c.sameSettings(old));
        for(String value:new String[]{"NaN","Infinity","-Infinity","181"}) {
            try{c.set("homeLon",value);fail(value);}catch(IllegalArgumentException expected) { }
        }
        c.set("projection","true");c.set("chargeaway","true");assertFalse(c.staticChanged(old));assertFalse(c.dstChanged(old));
    }
    @Test public void clearingHomeLeavesNoHome() {
        Config c=new Config(new Properties());
        c.set("homeLat","35.1");c.set("homeLon","-78.2");c.set("chargeaway","true");
        c.set("homeLat","");c.set("homeLon","");c.validateHome();
        assertFalse(c.hasHome());assertTrue(c.enabled("chargeaway"));
    }
    @Test public void projectionOpensOncePerConnectionAndUnknownDoesNotReopen() {
        ProjectionPolicy p=new ProjectionPolicy(0);
        assertEquals(1,p.observe(1));assertEquals(0,p.observe(1));assertEquals(0,p.observe(null));assertEquals(0,p.observe(1));
        assertEquals(0,p.observe(0));assertEquals(1,p.observe(1));assertEquals(2,p.observe(2));
    }
    @Test public void projectionRestartAndSimultaneousNoticesCannotStealFocusRepeatedly() {
        ProjectionPolicy p=new ProjectionPolicy(0);assertEquals(1,p.observe(3));assertEquals(0,p.observe(3));
        ProjectionPolicy restarted=new ProjectionPolicy(p.seen());assertEquals(0,restarted.observe(3));
        assertEquals(0,restarted.observe(255));assertEquals(0,restarted.observe(3));
        restarted.observe(0);assertEquals(2,restarted.observe(2));
    }
    @Test public void projectionReopensASessionThatSurvivedTheCarBeingOff() {
        ProjectionPolicy p=new ProjectionPolicy(0);assertEquals(2,p.observe(2));assertEquals(0,p.observe(2));
        p.newDrive();assertEquals(2,p.observe(2));assertEquals(0,p.observe(2));
        p.newDrive();assertEquals(0,p.observe(0));assertEquals(1,p.observe(1));
    }
}
