package com.tilepad;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.Adler32;
import org.junit.Test;
import static org.junit.Assert.*;

public class OdexCodeTest {
    // A minimal ODEX 036: header, a 16-byte DEX at 40, the dependency table at 56 (source mtime,
    // CRC, VM build, no dependencies) and 8 bytes of optimized data at 72, with a valid checksum.
    private static byte[] odex(int mtime, byte dexByte, byte optByte) {
        ByteBuffer b = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'d', 'e', 'y', '\n', '0', '3', '6', 0});
        b.putInt(40).putInt(16).putInt(56).putInt(16).putInt(72).putInt(8).putInt(0).putInt(0);
        for (int i = 0; i < 16; i++) b.put(dexByte);
        b.putInt(mtime).putInt(0x12345678).putInt(27).putInt(0);
        for (int i = 0; i < 8; i++) b.put(optByte);
        return withChecksum(b.array());
    }
    private static byte[] withChecksum(byte[] data) {
        Adler32 adler = new Adler32(); adler.update(data, 56, 24);
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(36, (int) adler.getValue());
        return data;
    }
    @Test public void aRebuildWithAnotherSourceTimeHasTheSameCodeHash() throws Exception {
        byte[] first = odex(1562608058, (byte) 1, (byte) 2), rebuilt = odex(1562610013, (byte) 1, (byte) 2);
        assertNotEquals(first[36], rebuilt[36]);
        assertNotNull(OdexCode.hash(first));
        assertEquals(OdexCode.hash(first), OdexCode.hash(rebuilt));
    }
    @Test public void anyCodeOrDependencyDifferenceChangesIt() throws Exception {
        String stock = OdexCode.hash(odex(1, (byte) 1, (byte) 2));
        assertNotEquals(stock, OdexCode.hash(odex(1, (byte) 3, (byte) 2)));
        assertNotEquals(stock, OdexCode.hash(odex(1, (byte) 1, (byte) 4)));
        byte[] crc = odex(1, (byte) 1, (byte) 2); crc[60] ^= 1;
        assertNotEquals(stock, OdexCode.hash(withChecksum(crc)));
    }
    @Test public void anInvalidFileHasNone() throws Exception {
        byte[] checksum = odex(1, (byte) 1, (byte) 2); checksum[36] ^= 1;
        assertNull(OdexCode.hash(checksum));
        byte[] magic = odex(1, (byte) 1, (byte) 2); magic[5] = '5';
        assertNull(OdexCode.hash(magic));
        byte[] truncated = new byte[79]; System.arraycopy(odex(1, (byte) 1, (byte) 2), 0, truncated, 0, 79);
        assertNull(OdexCode.hash(truncated));
        assertNull(OdexCode.hash(new byte[12]));
    }
}
