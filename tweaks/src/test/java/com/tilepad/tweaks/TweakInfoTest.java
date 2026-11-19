package com.tilepad.tweaks;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class TweakInfoTest {
    private static List<String> ids(TweakInfo[] rail) {
        List<String> out = new ArrayList<>();
        for (TweakInfo t : rail) out.add(t.id);
        return out;
    }
    @Test public void chargeAwayOnlyForACarThatCanBePluggedIn() {
        List<String> plugin = ids(TweakInfo.rail(true)), gas = ids(TweakInfo.rail(false));
        assertTrue(plugin.contains("chargeaway"));
        assertFalse(gas.contains("chargeaway"));
        // Everything else is the same, in the same order, still ending with Support and Package Manager.
        List<String> rest = new ArrayList<>(plugin); rest.remove("chargeaway");
        assertEquals(rest, gas);
        assertEquals(Arrays.asList("support", "updates"), gas.subList(gas.size() - 2, gas.size()));
        assertFalse(plugin.contains("nav"));
    }
    @Test public void packagePagesGoAfterTheTweaksAndAreFoundById() {
        List<TweakInfo> pages = Arrays.asList(TweakInfo.addon("addon:a.pkg", "A", ""), TweakInfo.addon("addon:b.pkg", "B", ""));
        List<TweakInfo> rail = TweakInfo.rail(pages, false);
        List<String> ids = idsOf(rail);
        List<String> fixed = ids(TweakInfo.rail(false));
        assertEquals(fixed.size() + 2, ids.size());
        assertEquals(Arrays.asList("addon:a.pkg", "addon:b.pkg", "support", "updates"), ids.subList(ids.size() - 4, ids.size()));
        assertEquals("without pages, the left pane is as before", fixed, idsOf(TweakInfo.rail(new ArrayList<TweakInfo>(), false)));
        assertEquals(ids.indexOf("addon:b.pkg"), TweakInfo.find(rail, "addon:b.pkg"));
        assertEquals(ids.indexOf("updates"), TweakInfo.find(rail, "updates"));
        assertEquals("a page whose package went: the first entry", 0, TweakInfo.find(rail, "addon:gone.pkg"));
    }
    private static List<String> idsOf(List<TweakInfo> rail) {
        List<String> out = new ArrayList<>();
        for (TweakInfo t : rail) out.add(t.id);
        return out;
    }
}
