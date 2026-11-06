package com.tilepad.tweakcore;

import android.content.Context;
import android.os.*;
import android.util.Xml;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/** Exact captured EV3 interface. Only the scheduling mode changes; all eight time fields survive. */
final class ScheduledCharging {
    private static final String PACKAGE = "com.hkmc.daudio.app.ev3", HEV = "com.hkmc.hev.IHEVService";
    private static final String APK_SHA = "106158a5281616aaeafc4bf84a0f043c71ed72f570d1a47f036cb188f99ea925";
    private static boolean checked;
    static void requireSupported() throws Exception {
        if (checked) return;
        Platform.requireVehicle();
        if (!APK_SHA.equals(Files.hash(new File("/system/app/HKMC_TAppEV3.apk"))))
            throw new IOException("The EV Settings interface differs from the captured firmware. Capture and submit a log to help add compatibility.");
        checked = true;
    }
    static int[] read() throws Exception {
        requireSupported(); IBinder service = Platform.service("com.hkmc.micom.hev", HEV);
        final CountDownLatch response = new CountDownLatch(1);
        final int[][] value = new int[1][];
        final boolean[] armed = new boolean[1];
        Binder listener = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString("com.hkmc.hev.IHEVListener"); return true; }
                if (code < 1 || code > 23) return super.onTransact(code, data, reply, flags);
                data.enforceInterface("com.hkmc.hev.IHEVListener");
                synchronized (value) {
                    if (code == 4 && armed[0] && response.getCount() != 0) {
                        int[] received = data.createIntArray(); value[0] = data.dataAvail() == 0 ? received : null;
                        response.countDown();
                    }
                }
                if ((flags & IBinder.FLAG_ONEWAY) == 0 && reply != null) reply.writeNoException();
                return true;
            }
        };
        try {
            call(service, 1, listener); // addListener, ignoring unsolicited cached callbacks during registration
            synchronized (value) { armed[0] = true; }
            call(service, 14, null); // request fresh economic charging data, not just the boot cache
            if (!response.await(1500, TimeUnit.MILLISECONDS)) throw new IOException("Waiting for fresh scheduled-charging feedback from the car.");
            int[] result; synchronized (value) { result = value[0]; }
            ChargeAwayPolicy.validate(result); return result;
        } finally {
            synchronized (value) { armed[0] = false; }
            call(service, 2, listener); // remove even after uncertain registration
        }
    }
    private static void call(IBinder service, int code, IBinder callback) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HEV);
            if (callback != null) data.writeStrongBinder(callback);
            if (!service.transact(code, data, reply, 0)) throw new IOException("Scheduled-charging read refused");
            reply.readException(); if (reply.dataAvail() != 0) throw new IOException("Unexpected scheduled-charging reply");
        } finally { data.recycle(); reply.recycle(); }
    }
    static Prepared prepare(int[] expected, int desired) throws Exception {
        int[] fresh = read();
        // Compare writable fields only: the other three fields are feedback, not settings.
        if (!Arrays.equals(Arrays.copyOf(expected, 9), Arrays.copyOf(fresh, 9)))
            throw new IOException("EV settings changed during the check; waiting for the next location poll.");
        return new Prepared(Platform.service("com.hkmc.micom.hev", HEV), ChargeAwayPolicy.request(fresh, desired));
    }
    static final class Prepared {
        private final IBinder service;
        private final int[] request;
        private Prepared(IBinder service, int[] request) { this.service = service; this.request = request; }
        void send() throws Exception {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(HEV); data.writeIntArray(request);
                if (!service.transact(30, data, reply, 0)) throw new IOException("Scheduled-charging request refused");
                reply.readException(); if (reply.dataAvail() != 0) throw new IOException("Unexpected scheduled-charging acknowledgement");
            } finally { data.recycle(); reply.recycle(); }
        }
    }
    static int preference(Context context) throws Exception {
        int uid = context.getPackageManager().getApplicationInfo(PACKAGE, 0).uid;
        // Match the stock app's UNECE choice of preference storage. Read as its owner;
        // this NEMESIS root channel deliberately has no DAC-override capability.
        boolean secure = (Boolean) Class.forName("com.hkmc.Feature").getMethod("hasFeature", String.class)
            .invoke(null, "com.hkmc.software.security.unece");
        String path = secure ? "/securedata/" + PACKAGE + "/economic_data_preference"
            : "/data/data/" + PACKAGE + "/shared_prefs/economic_data_preference.xml";
        String content = Platform.exec("/system/xbin/nemesis", "root", Integer.toString(uid), "-c", "cat " + path);
        if (secure) {
            JSONObject object = new JSONObject(content);
            int result = object.has("economic_only_economic_mode") ? object.getInt("economic_only_economic_mode") : 0;
            if (result != 0 && result != 1) throw new IOException("Unknown saved off-peak-only choice");
            return result;
        }
        XmlPullParser parser = Xml.newPullParser(); parser.setInput(new StringReader(content));
        boolean map = false; Integer result = null;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.DOCDECL) throw new IOException("Unexpected preference declaration");
            if (event != XmlPullParser.START_TAG) continue;
            if (parser.getName().equals("map")) map = true;
            if ("economic_only_economic_mode".equals(parser.getAttributeValue(null, "name"))) {
                if (!parser.getName().equals("int") || result != null) throw new IOException("Malformed off-peak-only choice");
                result = Integer.valueOf(parser.getAttributeValue(null, "value"));
            }
        }
        if (!map || (result != null && result != 0 && result != 1)) throw new IOException("Unknown saved off-peak-only choice");
        return result == null ? 0 : result; // The stock getter uses 0 when the key is absent.
    }
}
