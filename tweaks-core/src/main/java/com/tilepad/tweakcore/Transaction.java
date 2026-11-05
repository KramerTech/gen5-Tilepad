package com.tilepad.tweakcore;

import java.io.*;
import java.util.Properties;

/** Durable configuration commits; only changes with external effects need a recovery journal. */
public final class Transaction {
    public interface Apply { void run(Config config, Config previous, boolean rollback) throws Exception; }
    private static final String DELTA = "_tilepad.delta";
    private final File state;
    private final Apply apply;
    public Transaction(File state, Apply apply) { this.state = state; this.apply = apply; }
    public Config load() throws IOException { return new Config(Files.properties(new File(state, "config.properties"))); }
    /** Compatibility entry point; caller holds the state lock. */
    public Config updateHeaterOptions(String... pairs) throws IOException {
        requireNoPending();
        if (pairs.length == 0 || pairs.length % 2 != 0) throw new IllegalArgumentException("Expected option/value pairs");
        Config next = load();
        for (int i = 0; i < pairs.length; i += 2) {
            if (!Config.isHeaterOption(pairs[i])) throw new IllegalArgumentException("Not a heater option: " + pairs[i]);
            next.set(pairs[i], pairs[i + 1]);
        }
        if (!next.sameSettings(load())) Files.store(new File(state, "config.properties"), next.values);
        return next;
    }
    public void commit(Config next) throws Exception {
        requireNoPending();
        Config old = load();
        if (next.sameSettings(old)) return;
        if (!next.staticChanged(old) && !next.changedFrom(old, "dst")) {
            // Runtime options and heater enablement have no immediate system mutation.
            Files.store(new File(state, "config.properties"), next.values);
            return;
        }
        Properties intent = (Properties) next.values.clone(); intent.setProperty(DELTA, "true");
        Files.store(new File(state, "pending.properties"), intent);
        recover();
    }
    /** Restore/uninstall must audit all owned files, including journals from older releases. */
    public void commitFull(Config next) throws Exception {
        Files.store(new File(state, "pending.properties"), next.values);
        recover();
    }
    private void requireNoPending() throws IOException {
        if (new File(state, "pending.properties").exists()) throw new IOException("Finish the pending transaction first");
    }
    public void recover() throws Exception {
        File pending = new File(state, "pending.properties");
        if (!pending.isFile()) return;
        Properties intent = Files.properties(pending);
        boolean delta = "true".equals(intent.remove(DELTA));
        Config next = new Config(intent), old = load();
        try {
            // A missing marker is an older/full transaction: preserve its full recovery semantics.
            apply.run(next, delta ? old : null, false);
            Files.store(new File(state, "config.properties"), next.values);
        } catch (Exception failure) {
            try {
                apply.run(old, delta ? next : null, true);
                if (!pending.delete()) throw new IOException("Cannot retire failed transaction");
            } catch (Exception rollback) {
                throw new IOException(failure.getMessage() + " Recovery is incomplete: " + rollback.getMessage()
                    + ". Backups retained; use Restore from the USB uninstall bundle.", failure);
            }
            throw new IOException(failure.getMessage() + " Previous settings and owned files were restored.", failure);
        }
        if (!pending.delete()) throw new IOException("Configuration saved; transaction cleanup will retry on next command");
    }
}
