package com.tilepad.tweakcore;

/** Contrast between a color and text on it, as WCAG defines it. */
public final class Contrast {
    /** The two colors text on a swatch can take. */
    public static final int BLACK = 0x000000, WHITE = 0xffffff;

    private Contrast() {}

    /** The text color for rgb (0xRRGGBB), opaque: black or white, whichever has the higher contrast ratio. */
    public static int inkOn(int rgb) {
        return 0xff000000 | (ratio(rgb, BLACK) >= ratio(rgb, WHITE) ? BLACK : WHITE);
    }

    /** The contrast ratio of two colors (0xRRGGBB), from 1 to 21. */
    public static double ratio(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** The relative luminance of rgb (0xRRGGBB), from 0 to 1. */
    public static double luminance(int rgb) {
        double sum = 0;
        double[] weight = {0.2126, 0.7152, 0.0722};
        for (int k = 0; k < 3; k++) {
            double c = ((rgb >> (16 - 8 * k)) & 0xff) / 255.0;
            sum += weight[k] * (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        return sum;
    }
}
