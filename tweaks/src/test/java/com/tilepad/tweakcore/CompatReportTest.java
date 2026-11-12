package com.tilepad.tweakcore;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class CompatReportTest {
    private static final String LOGCAT =
        "11-11 21:00:00.100  1000  1010 I ActivityManager: START u0 {act=android.intent.action.VIEW dat=geo:37.1,-122.2 cmp=com.tilepad/.MainActivity} from pid 900\n"
        + "11-11 21:00:00.200  1000  1010 I ActivityManager: Start proc com.mobis.splitscreen.right for service com.mobis.splitscreen.right/.SplitScreenHome: pid=777 uid=1000 gids={}\n"
        + "11-11 21:00:00.300   777   777 I [OSD]SplitScreen: showOsd type=3 isSSOn=true\n"
        + "11-11 21:00:00.400   777   777 I [APP]SplitScreenService: artist : Some Band\n"
        + "11-11 21:00:00.500   777   777 I [APP]SplitScreenService: Call State - Ringing\n"
        + "11-11 21:00:00.600   777   777 D [APP]SplitScreenService: battery 80\n"
        + "11-11 21:00:00.700   555   555 I [OSD]SplitScreen: showOsd from a process restarted since\n"
        + "11-11 21:00:00.800   300   300 D ConsentScreenUI: Intent received, action : SHOW_CONSENT\n"
        + "11-11 21:00:00.900   300   300 D ConsentScreen: setDriverName : Alex\n"
        + "11-11 21:00:01.000   400   400 I tweaks: popover applied\n"
        + "11-11 21:00:01.100   777   777 I [OSD]SplitScreen: cancel osd for 555-123-4567\n"
        + "11-11 21:00:01.200   888   888 I Navigation: route to 1 Main St\n";
    @Test public void supportLogKeepsPanelEventsAndDropsPrivateLines() {
        Set<String> pids = new HashSet<>(Collections.singleton("777"));
        pids.addAll(CompatReport.startedPids(LOGCAT, CompatReport.PANELS));
        String kept = CompatReport.supportLog(LOGCAT, pids, 100);
        assertTrue(kept.contains("START u0 {act=android.intent.action.VIEW cmp=com.tilepad/.MainActivity}"));
        assertFalse(kept.contains("geo:"));
        assertTrue(kept.contains("showOsd type=3 isSSOn=true"));
        assertTrue(kept.contains("ConsentScreenUI: Intent received"));
        assertTrue(kept.contains("tweaks: popover applied"));
        assertTrue(kept.contains("cancel osd for #"));
        assertTrue(kept.contains("  777   777 I [OSD]SplitScreen: cancel"));
        for (String gone : new String[]{"Some Band", "Ringing", "battery", "Alex", "Main St", "123-4567"}) assertFalse(gone, kept.contains(gone));
        // 555 isn't a panel pid: no Start proc line named it.
        assertFalse(kept.contains("restarted since"));
        assertEquals(2, CompatReport.supportLog(LOGCAT, pids, 2).split("\n").length);
    }
    @Test public void startedPidsFollowsPanelProcessRestarts() {
        assertEquals(Collections.singleton("777"), CompatReport.startedPids(LOGCAT, CompatReport.PANELS));
    }
    @Test public void windowAndActivityDumpsKeepOnlyWhatIsOnScreen() {
        String windows = "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
            + "  Window #3 Window{41 u0 com.mobis.splitscreen.right}:\n    mDisplayId=0 mSession=Session{4 1:1000}\n"
            + "    mOwnerUid=1000 mShowToOwnerOnly=false package=com.mobis.splitscreen.right appop=NONE\n"
            + "    mAttrs=WM.LayoutParams{(0,0)(fillxfill) ty=2002 fl=#1820128}\n    Requested w=642 h=648\n"
            + "    mFrame=[1278,72][1920,720] last=[1278,72][1920,720]\n    mViewVisibility=0x0 mHaveFrame=true\n"
            + "  mCurrentFocus=Window{42 u0 com.tilepad/com.tilepad.MainActivity}\n";
        String kept = CompatReport.windowLines(windows);
        assertTrue(kept.contains("Window #3")); assertTrue(kept.contains("ty=2002")); assertTrue(kept.contains("mFrame=[1278,72]"));
        assertTrue(kept.contains("mViewVisibility=0x0")); assertTrue(kept.contains("mCurrentFocus"));
        assertFalse(kept.contains("mSession")); assertFalse(kept.contains("Requested"));
        String activities = "  Stack #0:\n    Task id #5\n      * TaskRecord{4 #5 A com.tilepad U 0}\n        Run #1: ActivityRecord{4 u0 com.tilepad/.MainActivity t5}\n  mResumedActivity: ActivityRecord{4 u0 com.tilepad/.MainActivity t5}\n      intent={act=VIEW dat=x}\n";
        String acts = CompatReport.activityLines(activities);
        assertTrue(acts.contains("Run #1")); assertTrue(acts.contains("mResumedActivity")); assertFalse(acts.contains("dat="));
    }
    @Test public void eachSupportLogGetsItsOwnName() {
        Calendar c = Calendar.getInstance(); c.set(2026, Calendar.NOVEMBER, 11, 21, 5, 9);
        assertEquals("tilepad-log-NEEV23.USA_V016-20261111-210509.zip", CompatReport.supportFileName("NEEV23.USA V016", c.getTime()));
    }
    @Test public void findsTheFirstRawUsbMount() {
        String mounts = "/dev/fuse /storage/usb0 fuse rw 0 0\n"
            + "/dev/block/vold/8:1 /mnt/media_rw/usb0 texfat rw,uid=1023 0 0\n"
            + "/dev/block/vold/8:17 /mnt/media_rw/usb1 vfat rw 0 0\n";
        assertEquals("/mnt/media_rw/usb0", CompatReport.usbMount(mounts));
        assertNull(CompatReport.usbMount("/dev/fuse /storage/usb0 fuse rw 0 0\n"));
    }
    @Test public void fileNameIsSafeForAnyProductString() {
        assertEquals("tilepad-compat-NEEV23.USA.0000.V015.012.260507.zip", CompatReport.fileName("NEEV23.USA.0000.V015.012.260507"));
        assertEquals("tilepad-compat-a_b_c.zip", CompatReport.fileName("a/b c"));
        assertEquals("tilepad-compat-unknown.zip", CompatReport.fileName(""));
    }
    @Test public void listsIdentityHashesAndEveryTweakWithItsReason() {
        Map<String, String> identity = new LinkedHashMap<>(), files = new LinkedHashMap<>();
        identity.put("ro.product.device", "wp_daudioplus_cvev_us");
        files.put("/system/framework/automotive.odex", "abc123");
        Properties status = new Properties();
        status.setProperty("dst", "true");
        status.setProperty("unavailable.chargeaway", "The EV Settings interface differs.\nNo change.");
        String text = CompatReport.text(identity, files, status);
        assertTrue(text.contains("ro.product.device=wp_daudioplus_cvev_us\n"));
        assertTrue(text.contains("/system/framework/automotive.odex=abc123\n"));
        assertTrue(text.contains("dst=true\n"));
        assertTrue(text.contains("chargeaway=false  The EV Settings interface differs. No change.\n"));
        for (String id : Config.IDS) assertTrue(id, text.contains("\n" + id + "="));
        assertTrue(text.contains(CompatReport.ISSUES));
    }
    @Test public void serviceDumpsKeepOnlyNumericSignals() {
        String dump = "USM Service\n  SWRC_STEERINGWHEELHEATINGOPT : 1\n  FRONTSEATHEAT_DRVOPTION : 2\n"
            + "  CONVENIENCE_STEERINGWHEELWARMER : -1\n  DIGITAL_OWNER_PHONE_NAME : Sam's iPhone\n"
            + "  DIGITAL_SHARED_PHONE_NAME_LIST[0 : \n  mHeatOnOff: 0\n  HeatDisp : 0x00\n";
        assertEquals("SWRC_STEERINGWHEELHEATINGOPT : 1\nFRONTSEATHEAT_DRVOPTION : 2\nCONVENIENCE_STEERINGWHEELWARMER : -1\n"
            + "mHeatOnOff: 0\nHeatDisp : 0x00\n", CompatReport.numericLines(dump));
    }
}
