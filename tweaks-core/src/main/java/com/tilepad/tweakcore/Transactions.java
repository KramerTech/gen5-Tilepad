package com.tilepad.tweakcore;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Binder transaction codes by method name, read from the firmware's own interface class
 * ({@code Interface$Stub.TRANSACTION_method}), to check the codes confirmed for each release
 * (Firmware) before one is sent. Reading the field initialises the Stub class, whose initialiser
 * on these head units only sets its constants; no method of it is called.
 */
final class Transactions {
    /** named(): the interface class isn't on this process's class path, so the code can't be checked. */
    static final int UNKNOWN = -1;
    /** named(): the interface class is there, but gives the method no valid code (a renamed or removed method). */
    static final int ABSENT = 0;
    private static final Map<String, Integer> NAMED = new HashMap<>();
    /** Interfaces whose classes aren't on the boot class path, and the system library that holds each. */
    private static final Map<String, String> LIBRARIES = new HashMap<>();
    static {
        LIBRARIES.put("com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService", "/system/framework/com.hkmc.ext.hcan.jar");
    }
    private Transactions() { }

    /**
     * The code descriptor's method has on this unit; UNKNOWN where its interface class isn't on this
     * process's class path, ABSENT where the class is there without a valid code for the method.
     * The descriptor is the interface's class name on these head units.
     */
    static int named(String descriptor, String method) {
        String key = descriptor + "." + method;
        synchronized (NAMED) {
            Integer known = NAMED.get(key);
            if (known != null) return known;
        }
        int code = read(descriptor + "$Stub", "TRANSACTION_" + method, Transactions.class.getClassLoader());
        String library = LIBRARIES.get(descriptor);
        if (code == UNKNOWN && library != null) code = read(descriptor + "$Stub", "TRANSACTION_" + method, libraryLoader(library));
        synchronized (NAMED) { NAMED.put(key, code); }
        return code;
    }

    /** A loader for a system library that has its optimized code beside it, so nothing is written; null off the car. */
    private static ClassLoader libraryLoader(String jar) {
        if (!new java.io.File(jar).isFile()) return null;
        try {
            return (ClassLoader) Class.forName("dalvik.system.PathClassLoader")
                .getConstructor(String.class, ClassLoader.class).newInstance(jar, Transactions.class.getClassLoader());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    static int read(String stub, String field, ClassLoader loader) {
        if (loader == null) return UNKNOWN;
        Class<?> c;
        try {
            c = Class.forName(stub, false, loader);
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return UNKNOWN;
        }
        try {
            Field f = c.getDeclaredField(field);
            f.setAccessible(true);
            int code = f.getInt(null);
            // FIRST_CALL_TRANSACTION to LAST_CALL_TRANSACTION
            return code >= 1 && code <= 0x00ffffff ? code : ABSENT;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return ABSENT;
        }
    }

    /**
     * The code to send, from the confirmed one and named(). A confirmed code is sent only if the
     * firmware gives the method that same code, or where its class can't be read here (UNKNOWN);
     * a class without the method (ABSENT) or with another code refuses it. 0: send nothing.
     */
    static int decide(int confirmed, int named) {
        return confirmed > 0 && (named == UNKNOWN || named == confirmed) ? confirmed : 0;
    }
}
