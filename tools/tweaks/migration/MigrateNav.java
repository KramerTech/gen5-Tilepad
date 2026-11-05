package com.tilepad.migration;

import com.tilepad.tweakcore.*;
import java.io.*;
import java.nio.channels.FileLock;

/** Root app_process entry. The existing installed APK supplies all production core classes. */
public final class MigrateNav {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the verified stock candidate path");
        Platform.initialize();
        try (RandomAccessFile lock = new RandomAccessFile(new File(Platform.STATE, "lock"), "rw");
             FileLock ignored = lock.getChannel().lock()) {
            LegacyNavMigration.adopt(Platform.STATE, new File(Platform.HOME, "tweaks/patches"),
                Files.read(new File(args[0])), new PatchManager.SystemOps() {
                    @Override public File file(String path) { return new File(path); }
                    @Override public void writable() throws IOException { throw new IOException("Migration refuses any /system change"); }
                    @Override public void finish() { }
                    @Override public void replace(File target, byte[] bytes) throws IOException { throw new IOException("Migration refuses any /system change"); }
                }, Platform.boot());
        }
        System.out.println("Migrated: nav=true, popover=false; /system unchanged; all other preferences preserved.");
    }
    private MigrateNav() { }
}
