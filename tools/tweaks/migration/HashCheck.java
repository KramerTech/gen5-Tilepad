package com.tilepad.migration;

import com.tilepad.tweakcore.Files;
import java.io.File;

/** Run with -Xmx64m against the real 154 MiB APK to catch whole-file hashing regressions. */
public final class HashCheck {
    public static void main(String[] args) throws Exception {
        String hash = Files.hash(new File(args[0]));
        if (!hash.equals(args[1])) throw new AssertionError("Unexpected APK digest: " + hash);
        System.out.println("PASS: large OEM APK SHA-256 verified with a 64 MiB heap.");
    }
}
