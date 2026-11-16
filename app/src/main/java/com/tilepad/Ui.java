package com.tilepad;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Dark theme tokens and small view factories. Every view is built in code. */
final class Ui {
    static final int BG = 0xff0b0d12;
    static final int TILE = 0xff181c24;
    static final int TILE_PRESSED = 0xff252b36;
    static final int SURFACE = 0xff232833;
    static final int SURFACE_PRESSED = 0xff313848;
    static final int LINE = 0xff2c3340;
    static final int TEXT = 0xfff2f4f7;
    static final int MUTED = 0xff8e97a8;
    static final int FAINT = 0xff5a6273;
    static final int ACCENT = 0xff4c8dff;
    static final int GREEN = 0xff34d27b;
    static final int AMBER = 0xffffb340;
    static final int RED = 0xffff5a4f;
    static final int PURPLE = 0xff8b6cff;
    static final int SCRIM = 0xcc05070a;
    /** Deeper accent and red for fills under white text: 4.5:1 or better, where ACCENT and RED give 2.9 and 2.8. */
    static final int ACCENT_SOLID = 0xff2563eb;
    static final int RED_SOLID = 0xffcc3326;
    /** Their pressed fills: darker still (6.1:1 and 6.4:1), so a press shows as a press. */
    static final int ACCENT_SOLID_PRESSED = 0xff1d4ed8;
    static final int RED_SOLID_PRESSED = 0xffa8291e;

    /** The fill to use under white text for a colour (the accent and red deepen; others stay). */
    static int solid(int color) {
        return color == ACCENT ? ACCENT_SOLID : color == RED ? RED_SOLID : color;
    }

    /** The pressed fill for colour's solid() fill; pressed for a colour that doesn't deepen. */
    static int solidPressed(int color, int pressed) {
        return color == ACCENT ? ACCENT_SOLID_PRESSED : color == RED ? RED_SOLID_PRESSED : pressed;
    }

    final Context context;
    private final float density;

    Ui(Context context) {
        this.context = context;
        density = context.getResources().getDisplayMetrics().density;
    }

    int dp(float value) { return Math.round(value * density); }

    static int tone(PreconPresenter.Tone tone) {
        switch (tone) {
            case READY: return ACCENT;
            case BUSY: return MUTED;
            case GOOD: return GREEN;
            case WARN: return AMBER;
            case BAD: return RED;
            default: return MUTED;
        }
    }

    static int alpha(int color, float alpha) {
        return Color.argb(Math.round(alpha * 255), Color.red(color), Color.green(color), Color.blue(color));
    }

    TextView text(String value, float sp, int color, boolean bold) {
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        v.setIncludeFontPadding(false);
        return v;
    }

    TextView line(String value, float sp, int color, boolean bold) {
        TextView v = text(value, sp, color, bold);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    ImageView glyph(Glyph.Kind kind, int color, int sizeDp) {
        ImageView v = new ImageView(context);
        v.setImageDrawable(new Glyph(kind, color, dp(sizeDp)));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    LinearLayout column() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout row() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.HORIZONTAL); return v; }

    GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    GradientDrawable outlined(int fill, float radiusDp, float strokeDp, int stroke) {
        GradientDrawable shape = rounded(fill, radiusDp);
        shape.setStroke(dp(strokeDp), stroke);
        return shape;
    }

    GradientDrawable circle(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    /** Pressed, TUNE-focused (activated) and resting states for any tappable surface. */
    Drawable pressable(int fill, int pressed, float radiusDp) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(pressed, radiusDp));
        states.addState(new int[]{android.R.attr.state_activated}, outlined(fill, radiusDp, 3, ACCENT));
        states.addState(new int[]{}, rounded(fill, radiusDp));
        return states;
    }

    Drawable pressableOutline(float radiusDp) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, outlined(TILE_PRESSED, radiusDp, 2, LINE));
        states.addState(new int[]{android.R.attr.state_activated}, outlined(Color.TRANSPARENT, radiusDp, 3, ACCENT));
        states.addState(new int[]{}, outlined(Color.TRANSPARENT, radiusDp, 2, LINE));
        return states;
    }

    @SuppressWarnings("deprecation")
    static void background(View view, Drawable drawable) { view.setBackgroundDrawable(drawable); }

    /**
     * A rounded pill button with an optional leading glyph. Under white text the accent and the red
     * get their deeper fills and darker pressed fills (solid, solidPressed) in place of fill and pressed.
     */
    LinearLayout button(String title, Glyph.Kind glyph, int fill, int pressed, int textColor) {
        LinearLayout v = row();
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(26), 0, dp(26), 0);
        v.setMinimumWidth(dp(150));
        boolean white = textColor == TEXT;
        background(v, pressable(white ? solid(fill) : fill, white ? solidPressed(fill, pressed) : pressed, 28));
        if (glyph != null) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(24), dp(24));
            p.rightMargin = dp(10);
            v.addView(glyph(glyph, textColor, 24), p);
        }
        v.addView(text(title, 19, textColor, true));
        v.setClickable(true);
        v.setFocusable(false);
        v.setContentDescription(title);
        return v;
    }

    /** A round icon-only button, e.g. back or the edit badges. */
    FrameLayout iconButton(Glyph.Kind kind, int fill, int pressed, int color, int sizeDp, int glyphDp) {
        FrameLayout v = new FrameLayout(context);
        StateListDrawable states = new StateListDrawable();
        GradientDrawable down = circle(pressed);
        GradientDrawable focus = circle(fill); focus.setStroke(dp(3), ACCENT);
        states.addState(new int[]{android.R.attr.state_pressed}, down);
        states.addState(new int[]{android.R.attr.state_activated}, focus);
        states.addState(new int[]{}, circle(fill));
        background(v, states);
        v.addView(glyph(kind, color, glyphDp), new FrameLayout.LayoutParams(dp(glyphDp), dp(glyphDp), Gravity.CENTER));
        v.setLayoutParams(new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        v.setClickable(true);
        v.setFocusable(false);
        return v;
    }
}
