package com.tilepad;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Outline icons drawn on a 24-unit grid with a 2-unit round stroke, tintable by color. */
final class Glyph extends Drawable {
    enum Kind {
        WIFI, WIFI_OFF, BATTERY_HEAT, TERMINAL, BUG, CAR, REBOOT, PLUS, MINUS, PENCIL, SEARCH, BACK,
        BACKSPACE, CHECK, CLOSE, ALERT, CHEVRON_DOWN, SHIFT, APPS, TWEAKS
    }

    private Kind kind;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final int size;
    private int color;
    private int signal = 3;

    Glyph(Kind kind, int color, int intrinsicSize) {
        this.kind = kind;
        this.size = intrinsicSize;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(2f);
        fill.setStyle(Paint.Style.FILL);
        setColor(color);
    }

    void setColor(int value) {
        color = value;
        invalidateSelf();
    }

    void setKind(Kind value) {
        kind = value;
        invalidateSelf();
    }

    /** Wi-Fi bars lit, 0–3. */
    void setSignal(int value) {
        signal = value;
        invalidateSelf();
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float side = Math.min(b.width(), b.height());
        canvas.save();
        canvas.translate(b.left + (b.width() - side) / 2f, b.top + (b.height() - side) / 2f);
        canvas.scale(side / 24f, side / 24f);
        stroke.setColor(color);
        fill.setColor(color);
        switch (kind) {
            case TWEAKS:
                for (int i = 0; i < 3; i++) {
                    float x = 5f + 7f * i, y = i == 1 ? 8f : 16f;
                    canvas.drawLine(x, 3f, x, y - 2.5f, stroke);
                    canvas.drawLine(x, y + 2.5f, x, 21f, stroke);
                    canvas.drawCircle(x, y, 2.5f, stroke);
                }
                break;
            case WIFI: wifi(canvas, false); break;
            case WIFI_OFF: wifi(canvas, true); break;
            case BATTERY_HEAT:
                rect(canvas, 2.5f, 6.5f, 18.5f, 17.5f, 2.5f);
                canvas.drawLine(21.25f, 10.5f, 21.25f, 13.5f, stroke);
                stroke.setStrokeWidth(1.6f);
                for (float x : new float[]{7f, 10.5f, 14f}) {
                    path.reset();
                    path.moveTo(x, 14.5f);
                    path.quadTo(x - 1.5f, 13.25f, x, 12f);
                    path.quadTo(x + 1.5f, 10.75f, x, 9.5f);
                    canvas.drawPath(path, stroke);
                }
                stroke.setStrokeWidth(2f);
                break;
            case TERMINAL:
                rect(canvas, 2.5f, 4f, 21.5f, 20f, 3f);
                poly(canvas, false, 7f, 9.5f, 10f, 12f, 7f, 14.5f);
                canvas.drawLine(12.5f, 14.5f, 17f, 14.5f, stroke);
                break;
            case BUG:
                oval.set(9f, 4.5f, 15f, 10.5f);
                canvas.drawArc(oval, 180f, 180f, false, stroke);
                canvas.drawLine(10f, 5.3f, 8.5f, 3f, stroke);
                canvas.drawLine(14f, 5.3f, 15.5f, 3f, stroke);
                rect(canvas, 7f, 9f, 17f, 21f, 5f);
                canvas.drawLine(12f, 12.5f, 12f, 18.5f, stroke);
                // Legs: out and up, straight out, out and down, mirrored.
                float[] legs = {12f, 10.5f, 15.5f, 15.5f, 19f, 20.5f};
                for (int i = 0; i < legs.length; i += 2) {
                    canvas.drawLine(7f, legs[i], 3.5f, legs[i + 1], stroke);
                    canvas.drawLine(17f, legs[i], 20.5f, legs[i + 1], stroke);
                }
                break;
            case CAR:
                poly(canvas, false, 5.5f, 10.5f, 7.4f, 6f, 16.6f, 6f, 18.5f, 10.5f);
                rect(canvas, 3f, 10.5f, 21f, 16.5f, 2f);
                canvas.drawLine(6.5f, 16.5f, 6.5f, 19f, stroke);
                canvas.drawLine(17.5f, 16.5f, 17.5f, 19f, stroke);
                canvas.drawCircle(7f, 13.5f, 1.1f, fill);
                canvas.drawCircle(17f, 13.5f, 1.1f, fill);
                break;
            case REBOOT: reboot(canvas); break;
            case PLUS:
                canvas.drawLine(12f, 5f, 12f, 19f, stroke);
                canvas.drawLine(5f, 12f, 19f, 12f, stroke);
                break;
            case MINUS: canvas.drawLine(6f, 12f, 18f, 12f, stroke); break;
            case PENCIL:
                poly(canvas, true, 16.5f, 3.5f, 20.5f, 7.5f, 8f, 20f, 3f, 21f, 4f, 16f);
                canvas.drawLine(14.5f, 5.5f, 18.5f, 9.5f, stroke);
                break;
            case SEARCH:
                canvas.drawCircle(10.5f, 10.5f, 6.5f, stroke);
                canvas.drawLine(15.5f, 15.5f, 20.5f, 20.5f, stroke);
                break;
            case BACK: poly(canvas, false, 15f, 5f, 8f, 12f, 15f, 19f); break;
            case BACKSPACE:
                poly(canvas, true, 8f, 5f, 21f, 5f, 21f, 19f, 8f, 19f, 2f, 12f);
                canvas.drawLine(11.5f, 9.5f, 16.5f, 14.5f, stroke);
                canvas.drawLine(16.5f, 9.5f, 11.5f, 14.5f, stroke);
                break;
            case CHECK: poly(canvas, false, 4.5f, 12.5f, 9.5f, 17.5f, 19.5f, 7f); break;
            case CLOSE:
                canvas.drawLine(6f, 6f, 18f, 18f, stroke);
                canvas.drawLine(18f, 6f, 6f, 18f, stroke);
                break;
            case ALERT:
                poly(canvas, true, 12f, 3.5f, 21.5f, 20f, 2.5f, 20f);
                canvas.drawLine(12f, 9.5f, 12f, 14f, stroke);
                canvas.drawCircle(12f, 17.2f, 1.2f, fill);
                break;
            case CHEVRON_DOWN: poly(canvas, false, 6f, 9f, 12f, 15f, 18f, 9f); break;
            case SHIFT: poly(canvas, true, 12f, 4f, 20f, 12.5f, 15.5f, 12.5f, 15.5f, 19.5f, 8.5f, 19.5f, 8.5f, 12.5f, 4f, 12.5f); break;
            case APPS:
                rect(canvas, 4f, 4f, 10.5f, 10.5f, 1.8f);
                rect(canvas, 13.5f, 4f, 20f, 10.5f, 1.8f);
                rect(canvas, 4f, 13.5f, 10.5f, 20f, 1.8f);
                rect(canvas, 13.5f, 13.5f, 20f, 20f, 1.8f);
                break;
        }
        canvas.restore();
    }

    private void wifi(Canvas canvas, boolean off) {
        float cx = 12f, cy = 19.5f;
        float[] radii = {4.5f, 9f, 13.5f};
        int dim = Ui.alpha(color, 0.28f);
        for (int i = 0; i < radii.length; i++) {
            stroke.setColor(!off && i < signal ? color : dim);
            float r = radii[i];
            oval.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(oval, 225f, 90f, false, stroke);
        }
        fill.setColor(off ? dim : color);
        canvas.drawCircle(cx, cy, 1.4f, fill);
        stroke.setColor(color);
        if (off) canvas.drawLine(3.5f, 3.5f, 20.5f, 20.5f, stroke);
    }

    private void reboot(Canvas canvas) {
        // Clockwise arc from about 2 o'clock round to 12 o'clock, arrowhead at the 12 o'clock end.
        float r = 8.5f, cx = 12f, cy = 12.5f;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(oval, 330f, 300f, false, stroke);
        float tipX = cx + 0.6f, tipY = cy - r;
        poly(canvas, false, tipX - 3.4f, tipY - 3f, tipX, tipY, tipX - 3.4f, tipY + 3f);
    }

    private void rect(Canvas canvas, float l, float t, float r, float b, float radius) {
        oval.set(l, t, r, b);
        canvas.drawRoundRect(oval, radius, radius, stroke);
    }

    private void poly(Canvas canvas, boolean close, float... xy) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        if (close) path.close();
        canvas.drawPath(path, stroke);
    }

    @Override public void setAlpha(int alpha) { stroke.setAlpha(alpha); fill.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { stroke.setColorFilter(filter); fill.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
