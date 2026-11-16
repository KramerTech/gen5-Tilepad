package com.tilepad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** Indeterminate ring: a quarter arc over a faint track, one revolution per second. */
final class SpinnerView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private boolean spinning;

    SpinnerView(Context context, float strokePx) {
        super(context);
        for (Paint p : new Paint[]{track, arc}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokePx);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        setColor(Ui.ACCENT);
        setVisibility(INVISIBLE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setColor(int color) {
        arc.setColor(color);
        track.setColor(Ui.alpha(color, 0.18f));
        invalidate();
    }

    void setSpinning(boolean value) {
        if (spinning == value) return;
        spinning = value;
        setVisibility(value ? VISIBLE : INVISIBLE);
        // What sits beside the ring may need to make room for it (StatusTile's pill).
        requestLayout();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float inset = arc.getStrokeWidth() / 2f;
        oval.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawOval(oval, track);
        if (!spinning) return;
        float angle = (SystemClock.uptimeMillis() % 1000L) * 0.36f;
        canvas.drawArc(oval, angle - 90f, 100f, false, arc);
        postInvalidateOnAnimation();
    }
}
