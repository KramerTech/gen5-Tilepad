package com.tilepad.tweakcore;

import java.io.*;
import java.security.MessageDigest;
import java.util.Properties;

/** KitKat-compatible, durable state writes. No java.nio.file dependency. */
public final class Files {
    public static byte[] read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
    public static void write(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
        if (!temp.renameTo(file)) throw new IOException("Cannot commit " + file);
    }
    public static Properties properties(File file) throws IOException {
        Properties p = new Properties();
        if (file.isFile()) try (InputStream in = new FileInputStream(file)) { p.load(in); }
        return p;
    }
    public static void store(File file, Properties p) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.store(out, "Tilepad Tweaks");
        write(file, out.toByteArray());
    }
    public static String hash(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    /** OEM APKs can exceed the Dalvik heap. Hash files without loading their contents. */
    public static String hash(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[32768];
                for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String hex(byte[] digest) {
        StringBuilder s = new StringBuilder();
        for (byte b : digest) s.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return s.toString();
    }
    private Files() { }
}
