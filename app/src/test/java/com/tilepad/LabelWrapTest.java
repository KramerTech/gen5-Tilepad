package com.tilepad;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class LabelWrapTest {
    @Test public void breaksAfterTheFirstWordWhenThatBalances() {
        assertEquals("Reboot\nHead Unit", LabelWrap.wrap("Reboot Head Unit"));
        assertEquals("Nemesis\nManager", LabelWrap.wrap("Nemesis Manager"));
        assertEquals("Battery\nWatchdog", LabelWrap.wrap("Battery Watchdog"));
        assertEquals("Add\nshortcut", LabelWrap.wrap("Add shortcut"));
    }
    @Test public void longerNamesBreakWhereTheLongerLineIsShortest() {
        assertEquals("Reboot the\nHead Unit Now", LabelWrap.wrap("Reboot the Head Unit Now"));
        assertEquals("Google\nPlay Music", LabelWrap.wrap("Google Play Music"));
    }
    @Test public void oneWordStaysOnOneLine() {
        assertEquals("Dashcam", LabelWrap.wrap("Dashcam"));
        assertEquals("Dashcam", LabelWrap.wrap("  Dashcam "));
        assertEquals("Car\nHome", LabelWrap.wrap("Car   Home"));
    }
}
