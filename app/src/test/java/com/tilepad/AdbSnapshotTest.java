package com.tilepad;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AdbSnapshotTest {
    @Test public void onWhileAdbdRunsWithATcpPort() {
        AdbSnapshot s = AdbSnapshot.read("running", "5555", "");
        assertEquals(AdbSnapshot.State.ON, s.state);
        assertEquals(5555, s.port);
    }

    @Test public void fallsBackToThePersistentPortOnlyWhenTheLiveOneIsUnset() {
        assertEquals(5555, AdbSnapshot.read("running", "", "5555").port);
        assertEquals(5555, AdbSnapshot.read("running", null, "5555").port);
        assertEquals(AdbSnapshot.State.OFF, AdbSnapshot.read("running", "-1", "5555").state);
    }

    @Test public void offWhenAdbdIsStoppedOrUsbOnly() {
        assertEquals(AdbSnapshot.State.OFF, AdbSnapshot.read("stopped", "5555", "").state);
        assertEquals(AdbSnapshot.State.OFF, AdbSnapshot.read("", "5555", "").state);
        assertEquals(AdbSnapshot.State.OFF, AdbSnapshot.read("running", "", "").state);
        assertEquals(AdbSnapshot.State.OFF, AdbSnapshot.read("running", "x", "").state);
        assertEquals(0, AdbSnapshot.read("stopped", "5555", "").port);
    }
    @Test public void onNeedsSomethingListeningOnThePortWhenTheTablesAreReadable() {
        String header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n";
        String listening = header + "   0: 00000000:15B3 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 1234 1\n";
        String connectedOnly = header + "   0: 1700A8C0:15B3 C800A8C0:D431 01 00000000:00000000 00:00000000 00000000     0        0 1235 1\n";
        assertEquals(AdbSnapshot.State.ON, AdbSnapshot.read("running", "5555", "", listening).state);
        assertEquals(AdbSnapshot.State.OFF, AdbSnapshot.read("running", "5555", "", header).state);
        assertEquals(AdbSnapshot.State.OFF, AdbSnapshot.read("running", "5555", "", connectedOnly).state);
        assertEquals(AdbSnapshot.State.ON, AdbSnapshot.read("running", "5555", "", null).state);  // unreadable: trust the properties
    }
}
