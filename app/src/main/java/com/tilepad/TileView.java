package com.tilepad;

import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A square grid tile: rounded card, content, and edit badges for removable tiles. */
class TileView extends FrameLayout {
    interface EditListener {
        void onRename(TileView tile);
        void onRemove(TileView tile);
    }

    final Ui ui;
    TileSpec spec;
    private final View rename, remove;

    TileView(Ui ui, TileSpec spec, final EditListener edits) {
        super(ui.context);
        this.ui = ui;
        this.spec = spec;
        setClipChildren(false);
        setClickable(true);
        setFocusable(false); // TUNE has its own highlight; avoid a second native focus ring.
        if (spec.kind == TileSpec.Kind.ADD) Ui.background(this, ui.pressableOutline(26));
        else Ui.background(this, ui.pressable(Ui.TILE, Ui.TILE_PRESSED, 26));

        rename = badge(Glyph.Kind.PENCIL, Ui.SURFACE, Gravity.TOP | Gravity.LEFT, "Rename");
        remove = badge(Glyph.Kind.MINUS, Ui.RED, Gravity.TOP | Gravity.RIGHT, "Remove");
        rename.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { edits.onRename(TileView.this); }
        });
        remove.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { edits.onRemove(TileView.this); }
        });
    }

    private View badge(Glyph.Kind kind, int fill, int gravity, String description) {
        int pressed = fill == Ui.RED ? 0xffd9443b : Ui.SURFACE_PRESSED;
        FrameLayout badge = ui.iconButton(kind, fill, pressed, Ui.TEXT, 44, 22);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), gravity);
        p.leftMargin = p.rightMargin = p.topMargin = ui.dp(8);
        badge.setContentDescription(description);
        badge.setVisibility(GONE);
        addView(badge, p);
        return badge;
    }

    /** Content goes beneath the badges. */
    void setContent(View content) {
        addView(content, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    void setEditing(boolean editing) {
        int visibility = editing && !spec.isCore() ? VISIBLE : GONE;
        rename.setVisibility(visibility);
        remove.setVisibility(visibility);
    }

    void setAvailable(boolean value) {
        float alpha = value ? 1f : 0.45f;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != rename && child != remove) child.setAlpha(alpha);
        }
    }

    /** Centered icon over a name: the layout for every tile that is not a status tile. */
    static TileView simple(Ui ui, TileSpec spec, EditListener edits, View icon, int iconDp, String label) {
        TileView tile = new TileView(ui, spec, edits);
        LinearLayout column = ui.column();
        column.setGravity(Gravity.CENTER);
        column.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        column.addView(icon, new LinearLayout.LayoutParams(ui.dp(iconDp), ui.dp(iconDp)));
        TextView name = ui.text(LabelWrap.wrap(label), 19, Ui.TEXT, true);
        name.setTag("label");
        // Always two lines tall, so icons line up whether a name wraps or not.
        name.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        name.setLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = ui.dp(16);
        column.addView(name, p);
        tile.setContent(column);
        tile.setContentDescription(label);
        return tile;
    }

    /** A colored rounded square holding a glyph, like an app icon. */
    static FrameLayout chip(Ui ui, Glyph.Kind kind, int color) {
        FrameLayout chip = new FrameLayout(ui.context);
        Ui.background(chip, ui.rounded(color, 22));
        chip.addView(ui.glyph(kind, Ui.TEXT, 42), new FrameLayout.LayoutParams(ui.dp(42), ui.dp(42), Gravity.CENTER));
        return chip;
    }

    static ImageView appIcon(Ui ui, Drawable icon) {
        ImageView v = new ImageView(ui.context);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (icon != null) v.setImageDrawable(icon);
        else v.setImageDrawable(new Glyph(Glyph.Kind.APPS, Ui.MUTED, ui.dp(64)));
        v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    void setLabel(String label) {
        TextView name = (TextView) findViewWithTag("label");
        if (name != null) name.setText(LabelWrap.wrap(label));
        setContentDescription(label);
    }
}
