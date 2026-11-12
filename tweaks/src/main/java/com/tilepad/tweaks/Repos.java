package com.tilepad.tweaks;

import com.tilepad.tweakcore.SelfUpdate;
import java.util.*;

/** Picking a GitHub repository to link in Package Manager, without Android: the account's list and what's typed. */
final class Repos {
    private Repos() { }

    /** One of an account's repositories; nemesis when it mentions NEMESIS, so it likely publishes a package. */
    static final class Listed { String name, description; boolean nemesis; }

    /**
     * The repositories whose names contain {@code filter} (ignoring case), NEMESIS ones first.
     * With no filter, every one when {@code all}, else only the NEMESIS ones when there are any.
     */
    static List<Listed> matching(List<Listed> repos, String filter, boolean all) {
        String f = filter.trim().toLowerCase(Locale.US);
        boolean any = false;
        for (Listed l : repos) any |= l.nemesis;
        List<Listed> out = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++)
            for (Listed l : repos)
                if (l.nemesis == (pass == 0) && (f.isEmpty() ? all || !any || l.nemesis : l.name.toLowerCase(Locale.US).contains(f))) out.add(l);
        return out;
    }

    /** The comma-separated owners list, as names. */
    static List<String> owners(String list) {
        List<String> out = new ArrayList<>();
        for (String name : list.split(",")) if (!name.trim().isEmpty()) out.add(name.trim());
        return out;
    }
    /** The list with {@code login} first (once, whatever its case), or without it when {@code remove}. */
    static String owners(String list, String login, boolean remove) {
        StringBuilder out = new StringBuilder(remove ? "" : login);
        for (String name : owners(list))
            if (!name.equalsIgnoreCase(login)) out.append(out.length() == 0 ? "" : ",").append(name);
        return out.toString();
    }

    /** {owner, repo} from a pasted owner/repo or github.com address; null when it names no repository. */
    static String[] pasted(String typed) {
        String all = typed.trim().replaceFirst("^(https?://)?(www\\.)?github\\.com/", "");
        String[] parts = all.replaceAll("/+", "/").replaceAll("^/|/$", "").split("/");
        if (parts.length < 2) return null;
        String name = parts[1].replaceFirst("\\.git$", "");
        return SelfUpdate.validRepo(parts[0] + "/" + name) ? new String[]{parts[0], name} : null;
    }
}
