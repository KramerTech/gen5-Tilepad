package com.tilepad;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Compact in-app keyboard, so search and rename work without a system IME. */
final class KeyPad extends LinearLayout {
    interface Listener {
        void onText(String text);
        void onBackspace();
        void onClear();
        void onDone();
    }

    private static final String[][] LETTERS = {
        {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
        {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
        {"z", "x", "c", "v", "b", "n", "m"},
    };
    private static final String[][] SYMBOLS = {
        {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
        {"-", "/", ":", "(", ")", "&", "@", "'", "+"},
        {".", ",", "?", "!", "#", "_", "*"},
    };

    private final Ui ui;
    private final Listener listener;
    private final String doneLabel;
    private boolean symbols, shift;

    KeyPad(Ui ui, String doneLabel, Listener listener) {
        super(ui.context);
        this.ui = ui;
        this.listener = listener;
        this.doneLabel = doneLabel;
        setOrientation(VERTICAL);
        build();
    }

    /** Capitalize the next letter (e.g. at the start of a name). */
    void setShift(boolean value) {
        if (shift == value) return;
        shift = value;
        build();
    }

    private void build() {
        removeAllViews();
        String[][] rows = symbols ? SYMBOLS : LETTERS;
        addRow(rows[0], null, null, 0f);
        addRow(rows[1], null, null, 0.5f);
        View shiftKey = symbols ? null : glyphKey(Glyph.Kind.SHIFT, shift ? Ui.ACCENT : Ui.SURFACE,
            new Runnable() { @Override public void run() { setShift(!shift); } }, "Shift");
        View back = glyphKey(Glyph.Kind.BACKSPACE, Ui.SURFACE,
            new Runnable() { @Override public void run() { listener.onBackspace(); } }, "Backspace");
        back.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) { listener.onClear(); return true; }
        });
        addRow(rows[2], shiftKey, back, 0f);

        LinearLayout bottom = rowLayout();
        bottom.addView(textKey(symbols ? "ABC" : "123", Ui.SURFACE, 16, new Runnable() {
            @Override public void run() { symbols = !symbols; build(); }
        }), weight(1.5f));
        bottom.addView(textKey("space", Ui.SURFACE, 16, new Runnable() {
            @Override public void run() { type(" "); }
        }), weight(6f));
        bottom.addView(textKey(doneLabel, Ui.ACCENT, 17, new Runnable() {
            @Override public void run() { listener.onDone(); }
        }), weight(2.5f));
        addView(bottom, rowParams());
    }

    private void addRow(String[] keys, View lead, View trail, float sidePad) {
        LinearLayout row = rowLayout();
        if (sidePad > 0) row.addView(new View(getContext()), weight(sidePad));
        if (lead != null) row.addView(lead, weight(1.5f));
        else if (trail != null) row.addView(new View(getContext()), weight(1.5f));
        for (final String key : keys) {
            final String shown = shift && !symbols ? key.toUpperCase(java.util.Locale.US) : key;
            row.addView(textKey(shown, Ui.SURFACE, 22, new Runnable() {
                @Override public void run() { type(shown); }
            }), weight(1f));
        }
        if (trail != null) row.addView(trail, weight(1.5f));
        if (sidePad > 0) row.addView(new View(getContext()), weight(sidePad));
        addView(row, rowParams());
    }

    private void type(String text) {
        listener.onText(text);
        if (shift) setShift(false);
    }

    private LinearLayout rowLayout() {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private LayoutParams rowParams() {
        LayoutParams p = new LayoutParams(-1, 0, 1f);
        p.topMargin = p.bottomMargin = ui.dp(3);
        return p;
    }

    private LayoutParams weight(float w) {
        LayoutParams p = new LayoutParams(0, -1, w);
        p.leftMargin = p.rightMargin = ui.dp(3);
        return p;
    }

    private TextView textKey(String label, int fill, float sp, final Runnable action) {
        TextView key = ui.text(label, sp, Ui.TEXT, fill == Ui.ACCENT);
        key.setGravity(Gravity.CENTER);
        // White text: an accent key (Done) gets the deeper fill.
        Ui.background(key, ui.pressable(Ui.solid(fill), Ui.solidPressed(fill, Ui.SURFACE_PRESSED), 10));
        click(key, action);
        return key;
    }

    private View glyphKey(Glyph.Kind kind, int fill, Runnable action, String description) {
        FrameLayout key = new FrameLayout(getContext());
        Ui.background(key, ui.pressable(fill, Ui.SURFACE_PRESSED, 10));
        key.addView(ui.glyph(kind, Ui.TEXT, 26), new FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER));
        key.setContentDescription(description);
        click(key, action);
        return key;
    }

    private static void click(View view, final Runnable action) {
        view.setClickable(true);
        view.setFocusable(false);
        view.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { action.run(); } });
    }
}
