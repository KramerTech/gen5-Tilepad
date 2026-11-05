package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class GroupedGridMathTest {
    @Test public void statusTilesFormSquareAndUsersStartAfterDivider() {
        GroupedGridMath m = GroupedGridMath.compute(1192, 388, 16, 150, 240, 4);
        assertTrue(m.grouped); assertEquals(4, m.userCols);
        assertEquals(m.left(0), m.left(2)); assertEquals(m.left(1), m.left(3));
        assertEquals(m.top(0), m.top(1)); assertEquals(m.top(2), m.top(3));
        assertEquals(2 * m.tile + m.gap, m.left(1) + m.tile - m.left(0));
        assertTrue(m.left(1) + m.tile < m.dividerX()); assertTrue(m.dividerX() < m.left(4));
        assertTrue(m.left(7) + m.tile <= 1192);
        assertEquals(m.left(4), m.left(8)); assertEquals(m.top(2), m.top(8));
    }
    @Test public void userSlotsAndDragTargetsAgreeAndCannotEnterStatusArea() {
        GroupedGridMath m = GroupedGridMath.compute(1192, 388, 16, 150, 240, 4);
        for (int i = 4; i < 22; i++) assertEquals(i, m.indexAt(m.left(i) + m.tile / 2f, m.top(i) + m.tile / 2f, 22));
        assertEquals(4, m.indexAt(-100, -100, 22));
        assertEquals(21, m.indexAt(99999, 99999, 22)); assertEquals(-1, m.indexAt(0, 0, 4));
    }
    @Test public void contentCountsOnlyUserRowsAndReservesSquareForFewerFeatures() {
        GroupedGridMath m = GroupedGridMath.compute(1192, 388, 16, 150, 240, 3);
        assertTrue(m.grouped); assertEquals(2 * m.tile + m.gap, m.contentHeight(4));
        assertEquals(2 * m.tile + m.gap, m.contentHeight(11));
        assertEquals(3 * m.tile + 2 * m.gap, m.contentHeight(12));
    }
    @Test public void compactScreensFallBackAndStillProtectStatusTiles() {
        GroupedGridMath m = GroupedGridMath.compute(320, 240, 16, 150, 240, 4);
        assertFalse(m.grouped); assertTrue(m.tile > 0); assertTrue(m.indexAt(0, 0, 8) >= 4);
    }
}
