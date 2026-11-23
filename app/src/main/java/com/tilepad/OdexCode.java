package com.tilepad;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.Adler32;

/**
 * An ODEX file's code hash: its SHA-256 with the two fields a rebuild of identical sources changes
 * zeroed, the header checksum and the source jar's recorded modification time. The embedded DEX,
 * optimized data and dependency SHA-1s all still count, so two files share a code hash only when
 * tools/compat/odex_same.py would call them "build-time only" (or identical). Tilepad's own, not
 * upstream; no Android APIs, so it runs in host tests.
 */
final class OdexCode {
    private static final byte[] MAGIC = {'d', 'e', 'y', '\n', '0', '3', '6', 0}, ZERO = new byte[4];

    /** The code hash, or null unless this is an ODEX 036 file whose layout and checksum are valid. */
    static String hash(byte[] odex) throws Exception {
        if (odex.length < 40) return null;
        for (int i = 0; i < MAGIC.length; i++) if (odex[i] != MAGIC[i]) return null;
        ByteBuffer header = ByteBuffer.wrap(odex, 8, 32).order(ByteOrder.LITTLE_ENDIAN);
        header.getInt(); header.getInt(); // the DEX's offset and length: hashed as they are
        long deps = header.getInt() & 0xffffffffL, depsLength = header.getInt() & 0xffffffffL;
        long opt = header.getInt() & 0xffffffffL, optEnd = opt + (header.getInt() & 0xffffffffL);
        header.getInt(); // flags
        long checksum = header.getInt() & 0xffffffffL;
        if (deps < 40 || depsLength < 4 || optEnd < deps + 4 || optEnd > odex.length) return null;
        // Dalvik's checksum: Adler-32 over the dependency table through the end of the optimized data.
        Adler32 adler = new Adler32();
        adler.update(odex, (int) deps, (int) (optEnd - deps));
        if (adler.getValue() != checksum) return null;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(odex, 0, 36); digest.update(ZERO);
        digest.update(odex, 40, (int) deps - 40); digest.update(ZERO);
        digest.update(odex, (int) deps + 4, odex.length - (int) deps - 4);
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) value.append(String.format(Locale.US, "%02x", b & 255));
        return value.toString();
    }
    static String hash(String path) throws Exception {
        File file = new File(path);
        byte[] odex = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            for (int read = 0, count; read < odex.length; read += count)
                if ((count = in.read(odex, read, odex.length - read)) < 0) return null;
        }
        return hash(odex);
    }
    private OdexCode() { }
}
