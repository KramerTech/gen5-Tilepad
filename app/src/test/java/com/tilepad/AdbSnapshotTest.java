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
}
