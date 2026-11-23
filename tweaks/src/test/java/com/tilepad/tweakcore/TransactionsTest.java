package com.tilepad.tweakcore;

import static org.junit.Assert.*;
import org.junit.Test;

public class TransactionsTest {
    /** Laid out as AIDL generates an interface: the codes are package-private constants of its Stub. */
    interface FakeService {
        abstract class Stub {
            static final int TRANSACTION_isDst = 0x03;
            private static final int TRANSACTION_setStep = 0xd5;
            static final int TRANSACTION_outOfRange = 0;
            static final String TRANSACTION_notAnInt = "x";
        }
    }
    private static final String FAKE = "com.tilepad.tweakcore.TransactionsTest$FakeService";

    @Test public void readsTheCodeByMethodName() {
        assertEquals(0x03, Transactions.named(FAKE, "isDst"));
        assertEquals(0xd5, Transactions.named(FAKE, "setStep"));
    }
    @Test public void aClassWithoutTheMethodIsAbsentNotUnknown() {
        assertEquals(Transactions.ABSENT, Transactions.named(FAKE, "missing"));
        assertEquals(Transactions.ABSENT, Transactions.named(FAKE, "outOfRange"));
        assertEquals(Transactions.ABSENT, Transactions.named(FAKE, "notAnInt"));
    }
    @Test public void aClassThatIsntThereIsUnknown() {
        assertEquals(Transactions.UNKNOWN, Transactions.named("com.hkmc.usm.IUSMService", "setFrontSeatHeatDrvStep"));
    }
    @Test public void aConfirmedCodeIsSentOnlyIfTheFirmwareAgrees() {
        assertEquals(0xd5, Transactions.decide(0xd5, Transactions.named(FAKE, "setStep")));
        assertEquals("the firmware gives the method another code", 0, Transactions.decide(0xd4, Transactions.named(FAKE, "setStep")));
        assertEquals("the class is there, the method isn't", 0, Transactions.decide(0xd5, Transactions.named(FAKE, "missing")));
        assertEquals("the class isn't on this class path: the confirmed code stands",
            0xd5, Transactions.decide(0xd5, Transactions.named("com.hkmc.usm.IUSMService", "setFrontSeatHeatDrvStep")));
        assertEquals("no confirmed code: nothing is sent", 0, Transactions.decide(0, Transactions.named(FAKE, "isDst")));
    }
}
