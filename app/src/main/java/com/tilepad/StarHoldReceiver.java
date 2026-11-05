package com.tilepad;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * A steering-wheel star hold opens Tilepad (the longstar service). At ~800 ms the MICOM also
 * flags the press as long and ModeManagerService sends this ordered broadcast, whose receivers
 * open Button Settings under Tilepad. Declared at priority 1000 so it is delivered first; it
 * drops the broadcast and passes it only to NEMESIS Manager's press-count trigger. A short press
 * sends the star's assigned action instead and is not affected.
 *
 * This has to be a manifest receiver: Android skips ordered broadcasts to a receiver registered
 * by the longstar daemon ("process crashing"), since that process isn't an attached app.
 */
public final class StarHoldReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!isOrderedBroadcast()) return;
        abortBroadcast();
        context.sendBroadcast(new Intent(intent).setComponent(
            new ComponentName("com.nemesis.manager", "com.nemesis.manager.SetupReceiver")));
    }
}
