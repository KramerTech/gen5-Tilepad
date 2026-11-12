package com.tilepad.tweakcore;

import static org.junit.Assert.*;

import java.io.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Before;
import org.junit.Test;

public class ZipProbeTest {
    private File dir;
    private int reads;

    @Before public void setUp() throws Exception { dir = java.nio.file.Files.createTempDirectory("zip-probe").toFile(); }

    @Test public void aPackageIsReadFromItsDescriptorWithoutReadingTheRest() throws Exception {
        File zip = new File(dir, "dashcam.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("NEMESIS.data")); z.write("[nemesis]\nid=dashcam\npackage-version=1.2.8\n".getBytes("UTF-8"));
            z.putNextEntry(new ZipEntry("app/Big.apk")); z.write(noise(3 << 20));
        }
        ZipProbe.Found f = ZipProbe.probe(counted(zip), false);
        assertEquals("dashcam", f.descriptor.getProperty("id"));
        assertEquals("1.2.8", f.descriptor.getProperty("package-version"));
        assertNull(f.inner);
        int read = reads;
        assertTrue("read " + read + " bytes", read < 80 << 10);
    }

    @Test public void aBundleIsFollowedToItsPackageStoredOrCompressed() throws Exception {
        File pkg = new File(dir, "inner.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(pkg))) {
            z.putNextEntry(new ZipEntry("app/Big.apk")); z.write(noise(1 << 20));
            z.putNextEntry(new ZipEntry("NEMESIS.data")); z.write("[nemesis]\nid=wireless.carplay.aa\npackage-version=1.2.0\n".getBytes("UTF-8"));
        }
        for (boolean stored : new boolean[]{true, false}) {
            File bundle = new File(dir, "bundle-" + stored + ".zip");
            byte[] inner = Files.read(pkg);
            try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(bundle))) {
                z.putNextEntry(new ZipEntry("NEMESIS.data")); z.write("[nemesis]\nversion=1.0\ntype=install\npath=packages/wireless.carplay.aa.zip\n".getBytes("UTF-8"));
                ZipEntry e = new ZipEntry("packages/wireless.carplay.aa.zip");
                if (stored) {
                    CRC32 crc = new CRC32(); crc.update(inner);
                    e.setMethod(ZipEntry.STORED); e.setSize(inner.length); e.setCompressedSize(inner.length); e.setCrc(crc.getValue());
                }
                z.putNextEntry(e); z.write(inner);
            }
            reads = 0;
            ZipProbe.Found f = ZipProbe.probe(counted(bundle), true);
            assertEquals("wireless.carplay.aa", f.descriptor.getProperty("id"));
            assertEquals("packages/wireless.carplay.aa.zip", f.inner);
            // Stored, only the inner package's directory and descriptor are read.
            if (stored) assertTrue("read " + reads, reads < 200 << 10);
            else assertNull(ZipProbe.probe(ZipProbe.of(bundle), false));
        }
    }

    @Test public void anythingElseIsNoPackage() throws Exception {
        File script = new File(dir, "usb.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(script))) {
            z.putNextEntry(new ZipEntry("NEMESIS.data")); z.write("[nemesis]\ntype=execute\npath=tilepad/update.sh\n".getBytes("UTF-8"));
        }
        assertNull(ZipProbe.probe(ZipProbe.of(script), true));
        File plain = new File(dir, "plain.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(plain))) { z.putNextEntry(new ZipEntry("README")); z.write(1); }
        assertNull(ZipProbe.probe(ZipProbe.of(plain), true));
        File junk = new File(dir, "junk.zip"); Files.write(junk, noise(1000));
        try { ZipProbe.probe(ZipProbe.of(junk), true); fail(); } catch (IOException expected) { }
        assertFalse(ZipProbe.inner("../x.zip")); assertFalse(ZipProbe.inner("/x.zip")); assertFalse(ZipProbe.inner("x.apk"));
        assertTrue(ZipProbe.inner("packages/dashcam.zip"));
    }

    private ZipProbe.Source counted(File zip) {
        final ZipProbe.Source s = ZipProbe.of(zip);
        return new ZipProbe.Source() {
            @Override public long size() { return s.size(); }
            @Override public byte[] read(long offset, int length) throws IOException { reads += length; return s.read(offset, length); }
            @Override public InputStream open(long offset, long length) throws IOException { reads += length; return s.open(offset, length); }
        };
    }
    private static byte[] noise(int n) { byte[] b = new byte[n]; new java.util.Random(1).nextBytes(b); return b; }
}
