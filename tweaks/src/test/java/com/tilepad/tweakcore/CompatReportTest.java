package com.tilepad.tweakcore;

import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.Test;

public class CompatReportTest {
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
