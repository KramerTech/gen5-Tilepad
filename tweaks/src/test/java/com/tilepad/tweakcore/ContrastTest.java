package com.tilepad.tweakcore;

import org.junit.Test;
import static org.junit.Assert.*;

/** The name on a preset's swatch: black or white, whichever has the higher contrast ratio there. */
public class ContrastTest {
    @Test public void lightColorsTakeBlackAndDarkOnesWhite() {
        assertEquals(0xff000000, Contrast.inkOn(0xffffff));
        assertEquals(0xffffffff, Contrast.inkOn(0x000000));
        // a bright teal: white on it would be under 2:1
        assertEquals(0xff000000, Contrast.inkOn(0x03c7c0));
        assertEquals(0xffffffff, Contrast.inkOn(0x8a25fc));
    }

    @Test public void theInkChosenIsTheOneWithMoreContrast() {
        int[] colors = {0xe5f2fa, 0x188efd, 0x7ed715, 0xfea811, 0xe93e3e, 0xbb0fec, 0x808080, 0x0000ff, 0xff0000, 0x00ff00};
        for (int rgb : colors) {
            int ink = Contrast.inkOn(rgb) & 0xffffff, other = ink == Contrast.BLACK ? Contrast.WHITE : Contrast.BLACK;
            assertTrue(Integer.toHexString(rgb), Contrast.ratio(rgb, ink) >= Contrast.ratio(rgb, other));
        }
    }

    @Test public void luminanceAndRatioAreWcags() {
        assertEquals(0.0, Contrast.luminance(0x000000), 1e-9);
        assertEquals(1.0, Contrast.luminance(0xffffff), 1e-9);
        assertEquals(0.2126, Contrast.luminance(0xff0000), 1e-4);
        assertEquals(0.7152, Contrast.luminance(0x00ff00), 1e-4);
        // a mid-tone, past the curve's linear part: 0x40 is 0.2509 before, 0.0513 after
        assertEquals(0.0513, Contrast.luminance(0x404040), 1e-4);
        assertEquals(21.0, Contrast.ratio(0x000000, 0xffffff), 1e-9);
        assertEquals(1.0, Contrast.ratio(0x808080, 0x808080), 1e-9);
    }
}
