package com.tilepad.tweaks.addon;

import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/** The rows packages' own pages are drawn with, added to the host's middle column. */
public final class Widgets {
    public interface ChoiceListener { void chose(int index); }

    private final PageHost host;

    public Widgets(PageHost host) { this.host = host; }

    int dp(float n) { return host.dp(n); }
    void add(View v) { host.controls().addView(v); }
    TextView text(String label, float size, int color) { return host.text(label, size, color); }
    public TextView note(String label, boolean problem) { return host.note(label, problem); }
    public void divider() { host.divider(host.controls()); }
    Button button(String label, String description, View.OnClickListener click) { return host.button(label, description, click); }
    /** Disabled buttons fade out: the stock background doesn't change when disabled. */
    static void enable(Button b, boolean enabled) { b.setEnabled(enabled); b.setAlpha(enabled ? 1f : .4f); }
    public boolean usable() { return host.ready() && !host.busy(); }

    /** A group of settings' name, in bold. */
    public void heading(String label) {
        TextView v = text(label, 26, host.ink());
        v.setTypeface(null, Typeface.BOLD);
        v.setPadding(dp(30), dp(16), dp(24), 0);
        add(v);
    }

    /**
     * Radio buttons, one per label. layout: TRUE one per line, FALSE side by side (as a page asks),
     * or null: side by side only while every label fits on one line in its share of the pane.
     * A word too wide for its share would otherwise break mid-word (Android 4.4 splits it, leaving
     * a letter alone on the next line).
     */
    public void choices(String[] labels, int checked, Boolean layout, final ChoiceListener listener) {
        RadioGroup group = new RadioGroup(host.activity());
        boolean vertical = layout != null ? layout : !fitSideBySide(labels);
        group.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        group.setBaselineAligned(false);
        group.setPadding(dp(26), 0, dp(12), 0);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(host.activity());
            b.setId(200 + i);
            b.setText(labels[i]);
            b.setTextColor(host.ink());
            b.setTextSize(22);
            b.setMinHeight(dp(vertical ? 56 : 64));
            b.setEnabled(usable());
            group.addView(b, new RadioGroup.LayoutParams(vertical ? -1 : 0, -2, vertical ? 0 : 1));
            if (i == checked) b.setChecked(true);
        }
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) { listener.chose(id - 200); }
        });
        add(group);
    }

    /** Whether each label fits on one line beside its radio circle in an equal share of the controls pane. */
    private boolean fitSideBySide(String[] labels) {
        if (labels.length <= 1) return true;
        int pane = paneWidth();
        if (pane <= 0) {
            int longest = 0;
            for (String label : labels) longest = Math.max(longest, label.length());
            return !((labels.length > 3 && labels[0].length() > 6) || longest > 16);
        }
        int share = (pane - dp(26) - dp(12)) / labels.length;
        RadioButton probe = new RadioButton(host.activity());
        probe.setTextSize(22);
        int around = probe.getCompoundPaddingLeft() + probe.getCompoundPaddingRight() + dp(4);
        for (String label : labels) {
            if (around + (int) Math.ceil(probe.getPaint().measureText(label)) > share) return false;
        }
        return true;
    }

    /** The controls pane's width as laid out (its scroller's), or 0 before it has one. */
    private int paneWidth() {
        android.view.ViewParent parent = host.controls().getParent();
        if (!(parent instanceof View)) return 0;
        android.view.ViewGroup.LayoutParams lp = ((View) parent).getLayoutParams();
        return lp != null && lp.width > 0 ? lp.width : 0;
    }

    /** A switch's row: a checkbox and its label; tapping the row runs onToggle. */
    public void toggle(String label, boolean checked, final Runnable onToggle) {
        LinearLayout row = new LinearLayout(host.activity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        row.setPadding(dp(30), dp(8), dp(18), dp(8));
        row.setBackground(host.stockDrawable("bt_list_selector_minimal", host.selector(false)));
        CheckBox check = new CheckBox(host.activity());
        check.setChecked(checked);
        check.setClickable(false);
        check.setFocusable(false);
        Drawable stockCheck = host.stockDrawable("eco30_common_checkbox", null);
        if (stockCheck != null) check.setButtonDrawable(stockCheck);
        check.setDuplicateParentStateEnabled(true);
        row.addView(check, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text(label, 26, host.ink());
        title.setPadding(dp(8), 0, 0, 0);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        boolean on = usable();
        row.setEnabled(on);
        row.setAlpha(on ? 1f : .5f);
        row.setFocusable(true);
        row.setContentDescription(label + ", " + (checked ? "on" : "off"));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (usable()) onToggle.run(); }
        });
        add(row);
    }

    /** A setting's name, then − reading + at the end of the same row. */
    public void stepper(String label, String lower, String raise, String reading, final Runnable down, final Runnable up) {
        LinearLayout row = new LinearLayout(host.activity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(30), dp(4), dp(18), dp(4));
        row.addView(text(label, 22, host.ink()), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(button("−", lower, new View.OnClickListener() {
            @Override public void onClick(View v) { down.run(); }
        }), new LinearLayout.LayoutParams(dp(62), dp(64)));
        TextView shown = text(reading, 24, host.ink());
        shown.setGravity(Gravity.CENTER);
        row.addView(shown, new LinearLayout.LayoutParams(dp(120), dp(64)));
        row.addView(button("+", raise, new View.OnClickListener() {
            @Override public void onClick(View v) { up.run(); }
        }), new LinearLayout.LayoutParams(dp(62), dp(64)));
        add(row);
    }

    /** A full-width button that runs onTap, inset and sized like the tweaks' own action buttons. */
    public void actionButton(String label, boolean enabled, final Runnable onTap) {
        Button b = button(label, label, new View.OnClickListener() {
            @Override public void onClick(View v) { if (usable()) onTap.run(); }
        });
        enable(b, usable() && enabled);
        host.action(b);
    }

    /** A setting's name, then a swatch of the color rgb and its name; tapping runs onTap (null: can't be changed now). */
    public void colorRow(String label, int rgb, String name, final Runnable onTap) {
        LinearLayout row = new LinearLayout(host.activity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        row.setPadding(dp(30), dp(4), dp(24), dp(4));
        row.setBackground(host.stockDrawable("bt_list_selector_minimal", host.selector(false)));
        row.addView(text(label, 22, host.ink()), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(swatch(rgb, false), new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView shown = text(name, 22, host.ink());
        shown.setPadding(dp(12), 0, 0, 0);
        row.addView(shown, new LinearLayout.LayoutParams(-2, -2));
        boolean on = usable() && onTap != null;
        row.setEnabled(on);
        row.setAlpha(on ? 1f : .5f);
        row.setFocusable(true);
        row.setContentDescription(label + ", " + name);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (usable() && onTap != null) onTap.run(); }
        });
        add(row);
        divider();
    }

    /** A swatch's side, the touch minimum, and the space around each. */
    private static final int SWATCH_DP = 48, SWATCH_MARGIN_DP = 3;
    /**
     * A preset's width, its name on it (two lines at most): what the colors leave at 48 dp, shared by
     * the columns, between these two.
     */
    private static final int PRESET_MIN_DP = 60, PRESET_MAX_DP = 100;
    /** Rows of presets at most (the colors' four), and colors on a row. */
    private static final int PRESET_ROWS = 4, PER_ROW = 16;

    /**
     * colorGrid, and beside the colors, presets (each named on its swatch, from presetNames) under
     * presetHeading ("Presets" when null), in columns of at most four; a preset's index is
     * colors.size() plus its place in presets. Swatches are 48 dp square, unless the screen is too
     * narrow for the window, when the colors share what there is. The window scrolls when it's
     * taller than the screen.
     */
    public void colorGrid(String heading, java.util.List<String> colors, java.util.List<String> names, String presetHeading,
                          final java.util.List<String> presets, java.util.List<String> presetNames, String chosen, final ChoiceListener listener) {
        final android.app.Dialog[] shown = new android.app.Dialog[1];
        boolean grouped = !presets.isEmpty();
        int columns = grouped ? (presets.size() + PRESET_ROWS - 1) / PRESET_ROWS : 0;
        int margin = dp(SWATCH_MARGIN_DP), presetW = dp(PRESET_MAX_DP);
        // the window's frame and padding take about 64 dp of the screen's width, the body's 48 dp
        int screen = host.activity().getResources().getDisplayMetrics().widthPixels - dp(64) - dp(48);
        if (grouped) {
            int left = screen - dp(24) - columns * 2 * margin - PER_ROW * (dp(SWATCH_DP) + 2 * margin);
            presetW = Math.max(dp(PRESET_MIN_DP), Math.min(presetW, left / columns));
        }
        int beside = grouped ? dp(24) + columns * (presetW + 2 * margin) : 0;
        int room = screen - beside;
        int side = Math.max(dp(16), Math.min(dp(SWATCH_DP), room / PER_ROW - 2 * margin));

        LinearLayout body = new LinearLayout(host.activity());
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(dp(24), dp(6), dp(24), dp(10));
        LinearLayout wheel = new LinearLayout(host.activity());
        wheel.setOrientation(LinearLayout.VERTICAL);
        body.addView(wheel, new LinearLayout.LayoutParams(-2, -2));
        if (grouped) wheel.addView(groupTitle("Colors"), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout row = null;
        for (int i = 0; i < colors.size(); i++) {
            if (i % PER_ROW == 0) {
                row = new LinearLayout(host.activity());
                wheel.addView(row, new LinearLayout.LayoutParams(-2, -2));
            }
            final int index = i;
            String hex = colors.get(i);
            View cell = swatch(Integer.parseInt(hex, 16), hex.equals(chosen));
            cell.setFocusable(true);
            cell.setContentDescription((names != null ? names.get(i) : "#" + hex) + (hex.equals(chosen) ? ", chosen" : ""));
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (shown[0] != null) shown[0].dismiss();
                    if (usable()) listener.chose(index);
                }
            });
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(side, side);
            at.setMargins(margin, margin, margin, margin);
            row.addView(cell, at);
        }
        if (grouped) {
            LinearLayout column = new LinearLayout(host.activity());
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
            gap.setMargins(dp(24), 0, 0, 0);
            body.addView(column, gap);
            column.addView(groupTitle(presetHeading == null ? "Presets" : presetHeading), new LinearLayout.LayoutParams(-2, -2));
            row = null;
            for (int i = 0; i < presets.size(); i++) {
                if (i % columns == 0) {
                    row = new LinearLayout(host.activity());
                    // a name on two lines mustn't move its swatch down to line its text up with the others
                    row.setBaselineAligned(false);
                    column.addView(row, new LinearLayout.LayoutParams(-2, -2));
                }
                final int index = colors.size() + i;
                String hex = presets.get(i), name = presetNames.get(i);
                boolean on = hex.equals(chosen);
                int rgb = Integer.parseInt(hex, 16);
                TextView cell = text(name, 14, com.tilepad.tweakcore.Contrast.inkOn(rgb));
                cell.setBackground(swatchDrawable(rgb, on));
                cell.setGravity(Gravity.CENTER);
                cell.setMaxLines(2);
                cell.setEllipsize(android.text.TextUtils.TruncateAt.END);
                cell.setPadding(dp(4), 0, dp(4), 0);
                cell.setFocusable(true);
                cell.setContentDescription(name + (on ? ", chosen" : ""));
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (shown[0] != null) shown[0].dismiss();
                        if (usable()) listener.chose(index);
                    }
                });
                LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(presetW, dp(SWATCH_DP));
                at.setMargins(margin, margin, margin, margin);
                row.addView(cell, at);
            }
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(host.activity());
        // centred in the window, which can be wider than the swatches
        scroll.addView(body, new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
        shown[0] = host.choose(heading, scroll);
    }

    /** A group's name over its swatches in the color window. */
    private TextView groupTitle(String name) {
        TextView title = text(name, 18, host.ink());
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(SWATCH_MARGIN_DP), dp(4), dp(SWATCH_MARGIN_DP), dp(2));
        return title;
    }

    /** A rounded square of the color rgb (0xRRGGBB), outlined thickly when chosen. */
    private View swatch(int rgb, boolean chosen) {
        View v = new View(host.activity());
        v.setBackground(swatchDrawable(rgb, chosen));
        return v;
    }

    private GradientDrawable swatchDrawable(int rgb, boolean chosen) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(6));
        d.setColor(0xff000000 | rgb);
        if (chosen) d.setStroke(dp(3), host.ink());
        else d.setStroke(dp(1), (host.muted() & 0x00ffffff) | 0x80000000);
        return d;
    }
}
