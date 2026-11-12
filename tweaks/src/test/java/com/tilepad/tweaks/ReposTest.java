package com.tilepad.tweaks;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class ReposTest {
    private static List<Repos.Listed> repos(String... names) {
        List<Repos.Listed> out = new ArrayList<>();
        for (String n : names) {
            Repos.Listed l = new Repos.Listed();
            l.nemesis = n.startsWith("*"); l.name = l.nemesis ? n.substring(1) : n; l.description = "";
            out.add(l);
        }
        return out;
    }
    private static List<String> names(List<Repos.Listed> list) {
        List<String> out = new ArrayList<>();
        for (Repos.Listed l : list) out.add(l.name);
        return out;
    }

    @Test public void withNothingTypedOnlyNemesisReposShowWhenThereAreAny() {
        List<Repos.Listed> r = repos("mercury", "*gen5-RearDashcam", "photo-sort", "*gen5-SmartTube");
        assertEquals(Arrays.asList("gen5-RearDashcam", "gen5-SmartTube"), names(Repos.matching(r, "  ")));
        assertEquals(Arrays.asList("mercury", "photo-sort"), names(Repos.matching(repos("mercury", "photo-sort"), "")));
    }

    @Test public void searchingCoversEveryRepoNemesisOnesFirst() {
        List<Repos.Listed> r = repos("gen5-notes", "*gen5-RearDashcam", "house-planner", "*gen5-SmartTube");
        assertEquals(Arrays.asList("gen5-RearDashcam", "gen5-SmartTube", "gen5-notes"), names(Repos.matching(r, "GEN5")));
        assertEquals(Arrays.asList("house-planner"), names(Repos.matching(r, "plan")));
        assertTrue(Repos.matching(r, "zzz").isEmpty());
    }

    @Test public void aPastedRepositoryIsRecognisedFromOneBox() {
        assertArrayEquals(new String[]{"KramerTech", "gen5-Tilepad"}, Repos.pasted("https://github.com/KramerTech/gen5-Tilepad/"));
        assertArrayEquals(new String[]{"zpoon", "gen5w-Evergreen"}, Repos.pasted("zpoon/gen5w-Evergreen.git"));
        assertNull(Repos.pasted("KramerTech"));
    }
}
