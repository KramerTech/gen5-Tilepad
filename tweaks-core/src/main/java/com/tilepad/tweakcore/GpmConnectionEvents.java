package com.tilepad.tweakcore;

import android.os.*;
import java.io.IOException;

/** Native callbacks work in app_process; this firmware drops its ordered broadcasts to root receivers. */
final class GpmConnectionEvents {
    private static final String SERVICE = "com.hkmc.gpm.IGpmInterfaces", LISTENER = "com.hkmc.gpm.IGpmListener";
    private IBinder service;
    private final Binder listener;
    GpmConnectionEvents(final Handler handler, final Runnable changed) {
        listener = new Binder() {
            @Override protected boolean onTransact(final int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(LISTENER); return true; }
                if (code != 1 && code != 2) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(LISTENER);
                final int value = data.readInt();
                if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected Android Auto callback");
                // Never re-enter the OEM service on its callback thread: it may hold a service lock.
                handler.post(new Runnable() { @Override public void run() {
                    System.out.println("projection native callback " + (code == 1 ? "connection=" : "serviceKilled=") + value);
                    changed.run();
                } });
                if ((flags & IBinder.FLAG_ONEWAY) == 0 && reply != null) reply.writeNoException();
                return true;
            }
        };
    }
    void ensureRegistered() throws Exception {
        IBinder next = Platform.service("gpm", SERVICE);
        if (next == service) return;
        stop();
        service = next;
        try { transact(next, 9); }
        catch (Exception e) { stop(); throw e; }
        System.out.println("projection native Android Auto listener registered");
    }
    void stop() {
        IBinder previous = service; service = null;
        if (previous != null) try { transact(previous, 10); }
        catch (Exception e) { System.out.println("projection native listener removal: " + e); }
    }
    private void transact(IBinder target, int code) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE); data.writeStrongBinder(listener);
            if (!target.transact(code, data, reply, 0)) throw new IOException("Android Auto listener request refused");
            reply.readException();
            if (reply.dataAvail() != 0) throw new IOException("Unexpected Android Auto listener reply");
        } finally { data.recycle(); reply.recycle(); }
    }
}
