package com.tilepad.tweakcore;

/**
 * Root commands that run a script from an installed NEMESIS package's folder. /data/nemesis/apps
 * can be written by any app, so a folder there is trusted only once the root shell is inside it:
 * the folder and each file named must be root's, a folder or a plain file, and writable by no one
 * else, or nothing runs and the command says error=untrusted (status 3). The folder itself mustn't be
 * a link (its real path is dir). Being inside the folder first ties the check to the folder the
 * command then uses. No Android, so it can be tested on the host.
 */
public final class PackageFolder {
    public static final String APPS = "/data/nemesis/apps";
    /** The error word when a package's files aren't root's alone: nothing was run. */
    public static final String UNTRUSTED = "untrusted";

    private PackageFolder() { }

    /** A subshell that goes into dir, checks it and files (relative to it), then runs then. */
    public static String run(String dir, String[] files, String then) {
        StringBuilder list = new StringBuilder(".");
        for (String f : files) list.append(' ').append(f);
        return "(cd " + quote(dir) + " || exit 3;"
            + " [ \"$(pwd -P)\" = " + quote(dir) + " ] || { echo error=" + UNTRUSTED + "; exit 3; };"
            + " for f in " + list + "; do"
            + " set -- $(ls -ld \"$f\" 2>/dev/null);"
            + " case \"$1\" in d?????????|-?????????) ;; *) echo error=" + UNTRUSTED + "; exit 3;; esac;"
            + " case \"$1\" in ?????w*|????????w*) echo error=" + UNTRUSTED + "; exit 3;; esac;"
            + " [ \"$2\" = root ] || { echo error=" + UNTRUSTED + "; exit 3; };"
            + " done; " + then + ")";
    }

    /** s single-quoted for the root shell. */
    public static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
