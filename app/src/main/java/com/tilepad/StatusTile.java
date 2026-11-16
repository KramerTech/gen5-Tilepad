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
    private final LinearLayout pillBox, rows;
    private final FrameLayout content;
    private final FrameLayout.LayoutParams iconParams, pillParams;
    private boolean compact, pillCompact;
    private String pillText;
    /** The width the tile was last measured at; -1 before the first. */
    private int measuredWidth = -1;

    /** The glyph's ring is 64 dp with the 44 dp glyph centred in it; these are its top offsets. */
    private static final int ICON_TOP = -6, COMPACT_ICON_TOP = -10;
    /** With the ring 10 dp left of the content's edge, where the ring and the glyph end. */
    private static final int RING_RIGHT = 54, GLYPH_RIGHT = 44;
    private static final int ROW_GAP = 6, COMPACT_ROW_GAP = 2;

    StatusTile(Ui ui, TileSpec spec, EditListener edits, Glyph.Kind kind, String captionText) {
        super(ui, spec, edits);
        content = new FrameLayout(ui.context);
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
        iconParams = new FrameLayout.LayoutParams(ui.dp(64), ui.dp(64), Gravity.TOP | Gravity.LEFT);
        iconParams.leftMargin = -ui.dp(10);
        // Up a little, so the glyph doesn't crowd the caption below it.
        iconParams.topMargin = ui.dp(ICON_TOP);
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
        pillParams = new FrameLayout.LayoutParams(-2, ui.dp(28), Gravity.TOP | Gravity.RIGHT);
        pillParams.topMargin = ui.dp(8);
        // Never over the glyph, or its ring while that shows (onMeasure).
        pillParams.leftMargin = ui.dp(GLYPH_RIGHT);
        content.addView(pillBox, pillParams);

        rows = ui.column();
        caption = ui.line(captionText, 14, Ui.MUTED, false);
        headline = ui.line("", 24, Ui.TEXT, true);
        detail = ui.line("", 17, Ui.TEXT, false);
        meta = ui.line("", 14, Ui.MUTED, false);
        addLine(rows, caption, 0);
        addLine(rows, headline, ROW_GAP);
        addLine(rows, detail, ROW_GAP);
        addLine(rows, meta, ROW_GAP);
        content.addView(rows, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContent(content);
    }

    /**
     * Fits the contents to the tile. The pill stops at the glyph, or at its spinner ring while that
     * shows, and a word too long for that room first gets a tighter pill, until the tile's width or
     * the pill's room changes. A short tile (a grid with page dots has them) can't fit the glyph
     * above the four rows with the usual gaps; then the rows close up and the glyph moves up,
     * instead of overlapping.
     */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int pillLeft = ui.dp(spinner.getVisibility() == VISIBLE ? RING_RIGHT : GLYPH_RIGHT);
        if (width != measuredWidth || pillLeft != pillParams.leftMargin) {
            measuredWidth = width;
            pillParams.leftMargin = pillLeft;
            if (pillCompact) setPillCompact(false);
        }
        super.onMeasure(widthSpec, heightSpec);
        android.text.Layout pillLayout = pill.getLayout();
        if (!pillCompact && pillBox.getVisibility() == VISIBLE && pillLayout != null && pillLayout.getEllipsisCount(0) > 0) {
            setPillCompact(true);
            super.onMeasure(widthSpec, heightSpec);
        }
        int inner = content.getMeasuredHeight() - content.getPaddingTop() - content.getPaddingBottom();
        int rowsAtUsualGaps = rows.getMeasuredHeight() + (compact ? 3 * ui.dp(ROW_GAP - COMPACT_ROW_GAP) : 0);
        int glyphBottom = ui.dp(ICON_TOP + (64 + 44) / 2);
        boolean tight = inner - rowsAtUsualGaps < glyphBottom + ui.dp(4);
        if (tight == compact) return;
        compact = tight;
        iconParams.topMargin = ui.dp(compact ? COMPACT_ICON_TOP : ICON_TOP);
        for (int i = 1; i < rows.getChildCount(); i++) {
            ((LinearLayout.LayoutParams) rows.getChildAt(i).getLayoutParams()).topMargin = ui.dp(compact ? COMPACT_ROW_GAP : ROW_GAP);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private void setPillCompact(boolean value) {
        pillCompact = value;
        pillBox.setPadding(ui.dp(value ? 8 : 10), 0, ui.dp(value ? 9 : 12), 0);
        ((LinearLayout.LayoutParams) pillDot.getLayoutParams()).rightMargin = ui.dp(value ? 5 : 7);
        pill.setTextSize(value ? 12 : 13);
    }

    private void addLine(LinearLayout parent, TextView view, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = ui.dp(topDp);
        parent.addView(view, p);
    }

    void setPill(String text, int color) {
        if (text == null) { pillBox.setVisibility(INVISIBLE); return; }
        pillBox.setVisibility(VISIBLE);
        if (pillCompact && !text.equals(pillText)) setPillCompact(false);
        pillText = text;
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
