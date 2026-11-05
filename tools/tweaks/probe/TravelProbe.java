package com.tilepad.tweakcore;

import android.content.Context;
import android.os.Looper;

/** Read-only parked-car checks; never saves Home, changes charging, or launches projection. */
public final class TravelProbe {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        ProjectionController.requireSupported();
        System.out.println("projection.interface=verified");
        java.lang.reflect.Method foreground = ProjectionController.class.getDeclaredMethod("foregroundPackage");
        foreground.setAccessible(true);
        Object top = foreground.invoke(new ProjectionController(context, new android.os.Handler()));
        System.out.println("projection.foregroundReadable=" + (top != null));
        System.out.println("projection.androidAutoStatus=" + Platform.integer("gpm", "com.hkmc.gpm.IGpmInterfaces", 1));
        System.out.println("projection.carPlayStatus=" + Platform.integer("dipo", "android.dipo.IDiPOManager", 2));
        int[] schedule = ScheduledCharging.read();
        System.out.println("schedule.fields=" + schedule.length);
        System.out.println("schedule.mode=" + schedule[8]);
        System.out.println("schedule.timesConfigured=" + ChargeAwayPolicy.configured(schedule));
        System.out.println("schedule.onlyOffPeak=" + ScheduledCharging.preference(context));
        try {
            VehicleLocation fix = VehicleLocationSource.current();
            System.out.println("location.usable=true");
            System.out.println("location.accuracyMetres=" + fix.accuracy);
        } catch (Exception e) { System.out.println("location.usable=false: " + e.getMessage()); }
    }
}
