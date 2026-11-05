package com.tilepad;

/**
 * Two-line tile names. A multi-word name always breaks onto two lines, at the word gap that
 * makes the longer line shortest ("Reboot\nHead Unit", "Reboot the\nHead Unit Now"); ties
 * keep the first line short. One word stays on one line.
 */
final class LabelWrap {
    static String wrap(String label) {
        String[] words = label.trim().split("\\s+");
        if (words.length < 2) return label.trim();
        int best = 1, bestLonger = Integer.MAX_VALUE;
        for (int i = 1; i < words.length; i++) {
            int longer = Math.max(join(words, 0, i).length(), join(words, i, words.length).length());
            if (longer < bestLonger) { best = i; bestLonger = longer; }
        }
        return join(words, 0, best) + "\n" + join(words, best, words.length);
    }

    private static String join(String[] words, int from, int to) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < to; i++) b.append(i > from ? " " : "").append(words[i]);
        return b.toString();
    }

    private LabelWrap() { }
}
