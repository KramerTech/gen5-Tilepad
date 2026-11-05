package com.tilepad;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Information-dense core tiles (Wi-Fi, preconditioning, ADB), all one layout: a glyph and status
 * pill on top, then four text rows anchored to the bottom -- caption (what the tile is), headline
 * (the state: On / Off / network name), detail (IP, port, elapsed time) and meta (a small extra or
 * a "Tap to…" hint). Every row keeps its height when empty, so the rows line up across tiles.
 */
abstract class StatusTile extends TileView {
    final Glyph glyph;
    final SpinnerView spinner;
    final TextView pill, caption, headline, detail, meta;
    private final View pillDot;
    private final LinearLayout pillBox;

    StatusTile(Ui ui, TileSpec spec, EditListener edits, Glyph.Kind kind, String captionText) {
        super(ui, spec, edits);
        FrameLayout content = new FrameLayout(ui.context);
        int pad = ui.dp(18);
        content.setPadding(pad, pad, pad, ui.dp(16));

        FrameLayout icon = new FrameLayout(ui.context);
        spinner = new SpinnerView(ui.context, ui.dp(3));
        icon.addView(spinner, new FrameLayout.LayoutParams(-1, -1));
        glyph = new Glyph(kind, Ui.MUTED, ui.dp(44));
        android.widget.ImageView image = new android.widget.ImageView(ui.context);
        image.setImageDrawable(glyph);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon.addView(image, new FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.CENTER));
        // The glyph sits centred in its 64 dp spinner ring; shift the ring so the glyph itself
        // starts at the same left edge as the caption text below it.
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(ui.dp(64), ui.dp(64), Gravity.TOP | Gravity.LEFT);
        iconParams.leftMargin = -ui.dp(10);
        // Up a little, so the glyph doesn't crowd the caption below it.
        iconParams.topMargin = -ui.dp(6);
        content.addView(icon, iconParams);

        pillBox = ui.row();
        pillBox.setGravity(Gravity.CENTER_VERTICAL);
        pillBox.setPadding(ui.dp(10), 0, ui.dp(12), 0);
        pillDot = new View(ui.context);
        LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8));
        dot.rightMargin = ui.dp(7);
        pillBox.addView(pillDot, dot);
        pill = ui.line("", 13, Ui.MUTED, true);
        pillBox.addView(pill);
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(-2, ui.dp(28), Gravity.TOP | Gravity.RIGHT);
        pillParams.topMargin = ui.dp(8);
        content.addView(pillBox, pillParams);

        LinearLayout text = ui.column();
        caption = ui.line(captionText, 14, Ui.MUTED, false);
        headline = ui.line("", 24, Ui.TEXT, true);
        detail = ui.line("", 17, Ui.TEXT, false);
        meta = ui.line("", 14, Ui.MUTED, false);
        addLine(text, caption, 0);
        addLine(text, headline, 6);
        addLine(text, detail, 6);
        addLine(text, meta, 6);
        content.addView(text, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContent(content);
    }

    private void addLine(LinearLayout parent, TextView view, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = ui.dp(topDp);
        parent.addView(view, p);
    }

    void setPill(String text, int color) {
        if (text == null) { pillBox.setVisibility(INVISIBLE); return; }
        pillBox.setVisibility(VISIBLE);
        pill.setText(text);
        pill.setTextColor(color);
        Ui.background(pillDot, ui.circle(color));
        Ui.background(pillBox, ui.rounded(Ui.alpha(color, 0.14f), 14));
    }

    /** Empty text hides the row but keeps its slot (see the class comment). */
    static void show(TextView view, String text) {
        view.setText(text == null ? "" : text);
        view.setVisibility(text == null || text.isEmpty() ? INVISIBLE : VISIBLE);
    }
}
