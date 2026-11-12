package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionSnapshotTest {
    @Test public void connectedAddressUsesWifiByteOrder() {
        ConnectionSnapshot value = ConnectionSnapshot.read(true, true, "\"Network\"", 0x2a0200c0);
        assertEquals(ConnectionSnapshot.State.CONNECTED, value.state);
        assertEquals("Network", value.network);
        assertEquals("192.0.2.42", value.ip);
    }
    @Test public void disconnectedAssociationDoesNotShowStaleIp() {
        ConnectionSnapshot value = ConnectionSnapshot.read(true, false, "Previous", 0x2a0200c0);
        assertEquals(ConnectionSnapshot.State.DISCONNECTED, value.state);
        assertEquals("—", value.ip);
    }
    @Test public void wifiOffAndNoDhcpAddressAreNotConnected() {
        assertEquals(ConnectionSnapshot.State.OFF, ConnectionSnapshot.read(false, true, "N", 12).state);
        assertEquals(ConnectionSnapshot.State.DISCONNECTED, ConnectionSnapshot.read(true, true, "N", 0).state);
    }
    @Test public void unknownSsidStillDisplaysValidIpAndUnknownStateIsNotConnected() {
        assertEquals("Unknown network", ConnectionSnapshot.read(true, true, "<unknown ssid>", 1).network);
        assertEquals(ConnectionSnapshot.State.UNKNOWN, ConnectionSnapshot.unknown().state);
    }
    @Test public void linkQualityOnlyAppliesWhenConnectedAndIsClamped() {
        ConnectionSnapshot value = ConnectionSnapshot.read(true, true, "N", 1).withLink(7, 72);
        assertEquals(3, value.signal);
        assertEquals(72, value.linkMbps);
        ConnectionSnapshot off = ConnectionSnapshot.read(false, false, null, 0).withLink(2, 72);
        assertEquals(-1, off.signal);
    }
    @Test public void onTheWayReplacesOffAndNoNetworkUntilConnected() {
        ConnectionSnapshot off = ConnectionSnapshot.read(false, false, null, 0);
        assertSame(off, off.onTheWay(false, false, false, null));
        assertEquals(ConnectionSnapshot.State.TURNING_ON, off.onTheWay(true, false, false, null).state);
        assertEquals(ConnectionSnapshot.State.TURNING_ON, off.onTheWay(false, false, true, null).state);
        ConnectionSnapshot none = ConnectionSnapshot.read(true, false, null, 0);
        assertSame(none, none.onTheWay(false, false, false, "\"Home\""));
        assertEquals("Home", none.onTheWay(false, true, false, "\"Home\"").network);
        assertEquals(ConnectionSnapshot.State.CONNECTING, none.onTheWay(false, true, false, "\"Home\"").state);
        assertEquals("Connecting…", none.onTheWay(false, false, true, "<unknown ssid>").network);
        ConnectionSnapshot up = ConnectionSnapshot.read(true, true, "Home", 1);
        assertSame(up, up.onTheWay(true, true, true, "Home"));
    }
}
