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
}
