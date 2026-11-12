package com.tilepad.tweakcore;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads a package's NEMESIS.data out of a zip without having all of it: the central directory at the
 * end says where the file is, then only its bytes are read. Check for Updates uses it on GitHub
 * release assets, which can be tens of megabytes, with a few small range requests each. A USB bundle
 * (NEMESIS.data type=install, path=packages/x.zip) is followed to the package inside it.
 */
public final class ZipProbe {
    /** Bytes of a zip that may not be here yet. */
    public interface Source {
        long size();
        byte[] read(long offset, int length) throws IOException;
        /** {@code length} bytes from {@code offset}, as a stream. */
        InputStream open(long offset, long length) throws IOException;
    }
    /** What a release asset holds: a package's descriptor, and where the package is inside a bundle (null when it is the package). */
    public static final class Found {
        public final Properties descriptor; public final String inner;
        Found(Properties descriptor, String inner) { this.descriptor = descriptor; this.inner = inner; }
    }
    static final class Entry { String name; int method; long compressed, size, header; }
    private static final int DESCRIPTOR_LIMIT = 65536;

    /**
     * The package in this zip, or in the bundle's package; null when it is neither. A compressed
     * package in a bundle has to be read from its start until NEMESIS.data, which can be most of it:
     * only when {@code stream} allows.
     */
    public static Found probe(Source s, boolean stream) throws IOException {
        List<Entry> entries = entries(s);
        Entry data = find(entries, "NEMESIS.data");
        if (data == null) return null;
        Properties d = SelfUpdate.descriptor(new String(read(s, data), "UTF-8"));
        if (d.getProperty("id") != null) return new Found(d, null);
        String path = d.getProperty("path");
        if (!"install".equals(d.getProperty("type")) || !inner(path)) return null;
        Entry nested = find(entries, path);
        if (nested == null) return null;
        long at = dataOffset(s, nested);
        Properties inside;
        if (nested.method == ZipEntry.STORED) {
            // Its bytes sit as they are in the bundle: read it like any other zip.
            Source pkg = slice(s, at, nested.size);
            Entry e = find(entries(pkg), "NEMESIS.data");
            inside = e == null ? null : SelfUpdate.descriptor(new String(read(pkg, e), "UTF-8"));
        } else if (stream) inside = streamed(s.open(at, nested.compressed));
        else return null;
        return inside == null || inside.getProperty("id") == null ? null : new Found(inside, path);
    }
    /** A bundle's package path: a zip, inside the bundle. */
    public static boolean inner(String path) {
        return path != null && path.matches("[A-Za-z0-9._/-]{1,200}\\.zip") && !path.startsWith("/") && !path.contains("..");
    }

    static List<Entry> entries(Source s) throws IOException {
        long size = s.size();
        int tail = (int) Math.min(size, 22 + 65535);
        long tailStart = size - tail;
        byte[] end = s.read(tailStart, tail);
        int eocd = -1;
        for (int i = tail - 22; i >= 0; i--) if (u32(end, i) == 0x06054b50L) { eocd = i; break; }
        if (eocd < 0) throw new ZipException("Not a zip file");
        long cdSize = u32(end, eocd + 12), cdOffset = u32(end, eocd + 16);
        if (cdOffset + cdSize > size || cdSize > (8 << 20)) throw new ZipException("Not a zip file");
        byte[] cd = cdOffset >= tailStart
            ? Arrays.copyOfRange(end, (int) (cdOffset - tailStart), (int) (cdOffset - tailStart + cdSize))
            : s.read(cdOffset, (int) cdSize);
        List<Entry> out = new ArrayList<>();
        for (int p = 0; p + 46 <= cd.length && u32(cd, p) == 0x02014b50L;) {
            Entry e = new Entry();
            e.method = u16(cd, p + 10); e.compressed = u32(cd, p + 20); e.size = u32(cd, p + 24);
            int name = u16(cd, p + 28), extra = u16(cd, p + 30), comment = u16(cd, p + 32);
            e.header = u32(cd, p + 42);
            if (p + 46 + name > cd.length) break;
            e.name = new String(cd, p + 46, name, "UTF-8");
            out.add(e);
            p += 46 + name + extra + comment;
        }
        return out;
    }
    static Entry find(List<Entry> entries, String name) {
        for (Entry e : entries) if (e.name.equals(name)) return e;
        return null;
    }
    /** Where an entry's data starts: after its local header, whose extra field can differ from the directory's. */
    static long dataOffset(Source s, Entry e) throws IOException {
        byte[] local = s.read(e.header, 30);
        if (u32(local, 0) != 0x04034b50L) throw new ZipException("Not a zip file");
        return e.header + 30 + u16(local, 26) + u16(local, 28);
    }
    static byte[] read(Source s, Entry e) throws IOException {
        if (e.size > DESCRIPTOR_LIMIT || e.compressed > DESCRIPTOR_LIMIT) throw new ZipException("Package metadata too large");
        // The local header, whose extra field can differ from the directory's, and the data in one read when it fits.
        int guess = (int) Math.min(30 + e.name.length() * 3 + 256 + e.compressed, s.size() - e.header);
        byte[] chunk = s.read(e.header, guess);
        if (chunk.length < 30 || u32(chunk, 0) != 0x04034b50L) throw new ZipException("Not a zip file");
        int start = 30 + u16(chunk, 26) + u16(chunk, 28);
        byte[] raw = start + e.compressed <= chunk.length ? Arrays.copyOfRange(chunk, start, start + (int) e.compressed)
            : s.read(e.header + start, (int) e.compressed);
        if (e.method == ZipEntry.STORED) return raw;
        if (e.method != ZipEntry.DEFLATED) throw new ZipException("Unsupported zip compression");
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(raw), new Inflater(true))) { return limited(in); }
    }
    /** A compressed package inside a bundle: its entries in order, until NEMESIS.data. */
    private static Properties streamed(InputStream raw) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new InflaterInputStream(raw, new Inflater(true)))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null;)
                if (e.getName().equals("NEMESIS.data")) return SelfUpdate.descriptor(new String(limited(z), "UTF-8"));
        }
        return null;
    }
    private static byte[] limited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096];
        for (int n; (n = in.read(b)) != -1;) {
            if (out.size() + n > DESCRIPTOR_LIMIT) throw new ZipException("Package metadata too large");
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
    static Source slice(final Source s, final long offset, final long length) {
        return new Source() {
            @Override public long size() { return length; }
            @Override public byte[] read(long at, int n) throws IOException { check(at, n); return s.read(offset + at, n); }
            @Override public InputStream open(long at, long n) throws IOException { check(at, n); return s.open(offset + at, n); }
            private void check(long at, long n) throws IOException { if (at < 0 || n < 0 || at + n > length) throw new ZipException("Not a zip file"); }
        };
    }
    /** A zip file that is here, for tests and the root side. */
    public static Source of(final File file) {
        return new Source() {
            @Override public long size() { return file.length(); }
            @Override public byte[] read(long offset, int length) throws IOException {
                try (RandomAccessFile f = new RandomAccessFile(file, "r")) { byte[] b = new byte[length]; f.seek(offset); f.readFully(b); return b; }
            }
            @Override public InputStream open(long offset, long length) throws IOException {
                return new ByteArrayInputStream(read(offset, (int) length));
            }
        };
    }
    private static int u16(byte[] b, int at) { return (b[at] & 255) | (b[at + 1] & 255) << 8; }
    private static long u32(byte[] b, int at) { return (u16(b, at) | (long) u16(b, at + 2) << 16) & 0xffffffffL; }
    private ZipProbe() { }
}
