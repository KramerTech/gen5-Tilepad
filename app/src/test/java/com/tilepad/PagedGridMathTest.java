package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class PagedGridMathTest {
    // The car's launcher viewport.
    private static PagedGridMath car(int fixed) { return PagedGridMath.compute(1192, 388, 16, 150, 240, fixed); }

    @Test public void fixedBlockIsThreeByTwoAndUsersStartAfterTheDivider() {
        PagedGridMath m = car(6);
        assertTrue(m.grouped); assertEquals(3, m.fixedCols); assertEquals(6, m.cols); assertEquals(6, m.firstCapacity());
        // Wi-Fi, ADB, NEMESIS over Tweaks, Precondition, Reboot.
        assertEquals(m.left(0), m.left(3)); assertEquals(m.left(2), m.left(5));
        assertEquals(m.top(0), m.top(2)); assertEquals(m.top(3), m.top(5)); assertTrue(m.top(3) > m.top(0));
        assertTrue(m.left(2) + m.tile < m.dividerX()); assertTrue(m.dividerX() < m.left(6));
        assertEquals(m.left(6), m.left(9)); assertEquals(m.top(3), m.top(9));
        assertTrue(m.left(8) + m.tile <= 1192);
    }
    @Test public void sixUserTilesFitOnPageOneAndTheSeventhStartsPageTwo() {
        PagedGridMath m = car(6);
        assertEquals(1, m.pages(12)); assertEquals(1192, m.contentWidth(12));
        assertEquals(2, m.pages(13)); assertEquals(2 * 1192, m.contentWidth(13));
        assertEquals(0, m.page(11)); assertEquals(1, m.page(12));
        // Page two has no fixed block: a full row from its own left edge.
        assertTrue(m.left(12) >= 1192 && m.left(12) < 1192 + 20);
        assertEquals(m.top(0), m.top(12)); assertEquals(m.left(12), m.left(18)); assertEquals(m.top(3), m.top(18));
        assertTrue(m.left(17) + m.tile <= 2 * 1192);
        assertEquals(2, m.pages(24)); assertEquals(3, m.pages(25));
    }
    @Test public void pagesSitAFullScreenApartSoNeighboursStayOutOfThePadding() {
        PagedGridMath m = PagedGridMath.compute(1192, 388, 16, 150, 240, 6, 1280);
        assertEquals(1280 + 1192, m.contentWidth(13));
        assertTrue(m.left(11) + m.tile <= 1192);            // page one ends inside its own width
        assertTrue(m.left(12) >= 1280);                     // page two starts a whole screen later
        for (int i = 6; i < 30; i++) assertEquals(i, m.indexAt(m.left(i) + m.tile / 2f, m.top(i) + m.tile / 2f, 30));
    }
    @Test public void dragTargetsMatchSlotsOnEveryPageAndNeverEnterTheFixedBlock() {
        PagedGridMath m = car(6);
        for (int i = 6; i < 30; i++) assertEquals(i, m.indexAt(m.left(i) + m.tile / 2f, m.top(i) + m.tile / 2f, 30));
        assertEquals(6, m.indexAt(m.left(0), m.top(0), 30));
        assertEquals(9, m.indexAt(m.left(5), m.top(5), 30));
        assertEquals(29, m.indexAt(99999, 99999, 30)); assertEquals(-1, m.indexAt(0, 0, 6));
    }
    @Test public void fewerFixedTilesStillFormABlock() {
        PagedGridMath m = car(5);
        assertTrue(m.grouped); assertEquals(3, m.fixedCols);
        assertEquals(m.top(0), m.top(5)); assertTrue(m.dividerX() < m.left(5));  // first user tile, top row
        assertEquals(m.left(3), m.left(0)); assertEquals(m.top(3), m.top(4));
    }
    @Test public void compactScreensFallBackAndStillProtectFixedTiles() {
        PagedGridMath m = PagedGridMath.compute(320, 240, 16, 150, 240, 6);
        assertFalse(m.grouped); assertTrue(m.tile > 0); assertTrue(m.indexAt(0, 0, 12) >= 6);
        for (int i = 6; i < 12; i++) assertEquals(i, m.indexAt(m.left(i) + m.tile / 2f, m.top(i) + m.tile / 2f, 12));
    }
}
