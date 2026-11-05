package com.tilepad;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class TileOrderTest {
    private static String ids(List<TileSpec> tiles) {
        StringBuilder out = new StringBuilder();
        for (TileSpec t : tiles) out.append(t.id).append(' ');
        return out.toString().trim();
    }

    @Test public void freshInstallHasEveryCoreTileInDefaultOrder() {
        assertEquals("core:wifi core:adb core:precondition core:tweaks core:nemesis core:car_home core:reboot core:add",
            ids(TileOrder.parse(null)));
        assertEquals(ids(TileOrder.parse(null)), ids(TileOrder.parse("garbage")));
    }

    @Test public void roundTripKeepsOrderLabelsAndLauncherFlag() {
        List<TileSpec> tiles = TileOrder.add(TileOrder.parse(null),
            TileSpec.shortcut("com.mnsoft.navi/.NaviApp", true, "My\tnav\\map\nx"));
        assertEquals("sc:com.mnsoft.navi/.NaviApp", tiles.get(7).id);
        tiles = TileOrder.move(tiles, 7, 0);
        List<TileSpec> back = TileOrder.parse(TileOrder.serialize(tiles));
        assertEquals(ids(tiles), ids(back));
        assertEquals("My\tnav\\map\nx", back.get(0).label);
        assertTrue(back.get(0).launcher);
        assertEquals("com.mnsoft.navi/.NaviApp", back.get(0).component);
    }

    @Test public void shortcutsGoBeforeTheAddTileWhereverItIs() {
        List<TileSpec> tiles = TileOrder.move(TileOrder.parse(null), 7, 1); // Add moved to second
        tiles = TileOrder.add(tiles, TileSpec.shortcut("a/.A", false, null));
        assertEquals("sc:a/.A", tiles.get(1).id);
        assertEquals(TileSpec.Kind.ADD, tiles.get(2).kind);
        assertEquals(9, TileOrder.add(tiles, TileSpec.shortcut("a/.A", false, "dup")).size());
    }

    @Test public void coreTilesCannotBeRemovedOrRenamed() {
        List<TileSpec> tiles = TileOrder.parse(null);
        assertEquals(8, TileOrder.remove(tiles, "core:reboot").size());
        assertNull(TileOrder.rename(tiles, "core:wifi", "Net").get(0).label);
        tiles = TileOrder.add(tiles, TileSpec.shortcut("a/.A", false, null));
        assertEquals("Mine", TileOrder.find(TileOrder.rename(tiles, "sc:a/.A", " Mine "), "sc:a/.A").label);
        assertNull(TileOrder.find(TileOrder.rename(tiles, "sc:a/.A", "  "), "sc:a/.A").label);
        assertEquals(8, TileOrder.remove(tiles, "sc:a/.A").size());
    }

    @Test public void missingCoreTilesComeBackAndDuplicatesAndJunkAreDropped() {
        String stored = "tiles/1\nSHORTCUT\ta/.A\t0\t\nREBOOT\t\t0\t\nSHORTCUT\ta/.A\t1\tx\nBOGUS\t\t0\t\n"
            + "SHORTCUT\tnoslash\t0\t\nWIFI\t\t0\n";
        List<TileSpec> tiles = TileOrder.parse(stored);
        assertEquals("core:wifi core:adb core:precondition core:tweaks core:nemesis core:car_home sc:a/.A core:add core:reboot", ids(tiles));
        assertEquals("tiles/1\n", TileOrder.serialize(java.util.Collections.<TileSpec>emptyList()));
        for (TileSpec.Kind kind : TileSpec.CORE) assertEquals(1, countKind(tiles, kind));
    }

    @Test public void existingInstallsGetTheAdbTileBeforePreconditioning() {
        String stored = "tiles/1\nWIFI\t\t0\t\nPRECONDITION\t\t0\t\nNEMESIS\t\t0\t\nCAR_HOME\t\t0\t\n"
            + "REBOOT\t\t0\t\nADD\t\t0\t\n";
        assertEquals("core:wifi core:adb core:precondition core:tweaks core:nemesis core:car_home core:reboot core:add",
            ids(TileOrder.parse(stored)));
    }

    @Test public void reorderFollowsDragOrderAndKeepsUnknownTiles() {
        List<TileSpec> tiles = TileOrder.parse(null);
        List<TileSpec> out = TileOrder.reorder(tiles, Arrays.asList("core:add", "core:wifi", "nope"));
        assertEquals("core:add core:wifi core:adb core:precondition core:tweaks core:nemesis core:car_home core:reboot", ids(out));
    }

    @Test public void existingCustomLayoutGetsTweaksAfterPreconditionWithoutLosingShortcuts() {
        String stored = "tiles/1\nADD\t\t0\t\nSHORTCUT\tx/.A\t1\tMine\nREBOOT\t\t0\t\n"
            + "WIFI\t\t0\t\nADB\t\t0\t\nNEMESIS\t\t0\t\nCAR_HOME\t\t0\t\nPRECONDITION\t\t0\t\n";
        List<TileSpec> tiles = TileOrder.parse(stored);
        assertEquals("core:add sc:x/.A core:reboot core:wifi core:adb core:nemesis core:car_home core:precondition core:tweaks", ids(tiles));
        assertEquals("Mine", tiles.get(1).label);
        assertEquals(tiles.size(), TileOrder.remove(tiles, "core:tweaks").size());
    }

    private static int countKind(List<TileSpec> tiles, TileSpec.Kind kind) {
        int n = 0;
        for (TileSpec t : tiles) if (t.kind == kind) n++;
        return n;
    }
}
