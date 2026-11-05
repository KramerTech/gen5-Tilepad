package com.tilepad;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;

/**
 * Square tiles in rows sized to the visible height, inside a vertical {@link ScrollView}.
 * Long-press a tile to enter edit mode and drag it; in edit mode any tile drags right away.
 * Tapping empty space, or {@link #setEditing(false)}, leaves edit mode.
 */
final class TileGrid extends ViewGroup {
    interface Listener {
        void onEditingChanged(boolean editing);
        void onOrderChanged(List<TileView> order);
    }

    private final List<TileView> order = new ArrayList<>();
    private final int gap, minTile, maxTile, slop, edge;
    private Listener listener;
    private GroupedGridMath math;
    private int fixedCount, pinnedScroll;
    private final Paint separator = new Paint();
    private int viewportHeight, knobIndex = -1;
    private boolean editing, backgroundDown;
    private TileView dragging, pressedInEdit;
    private float touchX, touchY, downX, downY, grabX, grabY;
    private ValueAnimator wobble;

    TileGrid(Context context, Ui ui) {
        super(context);
        gap = ui.dp(16);
        minTile = ui.dp(150);
        maxTile = ui.dp(240);
        edge = ui.dp(56);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(false);
        setChildrenDrawingOrderEnabled(true);
        setWillNotDraw(false);
        separator.setColor(Ui.LINE);
        separator.setStrokeWidth(ui.dp(1));
    }

    void setListener(Listener value) { listener = value; }

    void setTiles(List<TileView> tiles) {
        removeAllViews();
        order.clear();
        // Keep the status square predictable while preserving the relative order
        // of every other saved tile and shortcut.
        for (TileSpec.Kind kind : new TileSpec.Kind[]{TileSpec.Kind.WIFI, TileSpec.Kind.ADB, TileSpec.Kind.TWEAKS, TileSpec.Kind.PRECONDITION})
            for (TileView tile : tiles) if (tile.spec.kind == kind) order.add(tile);
        fixedCount = order.size();
        for (TileView tile : tiles) if (!tile.spec.isStatus()) order.add(tile);
        for (TileView tile : order) {
            addView(tile);
            tile.setEditing(editing);
        }
        if (knobIndex >= order.size()) knobIndex = order.size() - 1;
        applyKnob();
    }

    List<TileView> tiles() { return order; }

    /** The visible height tiles are sized against; set by {@link Scroll}. */
    void setViewportHeight(int height) {
        if (height == viewportHeight) return;
        viewportHeight = height;
        // Called from the parent's onMeasure: skip the measure cache, don't re-request layout.
        forceLayout();
    }

    boolean isEditing() { return editing; }

    void setEditing(boolean value) {
        if (editing == value) return;
        editing = value;
        for (TileView tile : order) tile.setEditing(value);
        if (value) startWobble(); else stopWobble();
        if (listener != null) listener.onEditingChanged(value);
    }

    /** Long-press entry point: enter edit mode and pick the tile up under the finger. */
    void beginDrag(TileView tile) {
        setEditing(true);
        if (tile.spec.isStatus()) return;
        if (dragging == null) startDrag(tile);
    }

    // ---- layout ----

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = viewportHeight > 0 ? viewportHeight : MeasureSpec.getSize(heightSpec);
        math = GroupedGridMath.compute(width, height, gap, minTile, maxTile, fixedCount);
        int exact = MeasureSpec.makeMeasureSpec(math.tile, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(exact, exact);
        setMeasuredDimension(width, Math.max(height, math.contentHeight(order.size())));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < order.size(); i++) place(order.get(i), i);
    }

    private void place(View view, int index) {
        int left = math.left(index), top = math.top(index);
        view.layout(left, top, left + math.tile, top + math.tile);
        if (index < fixedCount) view.setTranslationY(math.grouped ? pinnedScroll : 0);
    }

    void setPinnedScroll(int scrollY) {
        pinnedScroll = scrollY;
        if (math != null) for (int i = 0; i < fixedCount; i++) order.get(i).setTranslationY(math.grouped ? scrollY : 0);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (math != null && math.grouped && order.size() > fixedCount)
            canvas.drawLine(math.dividerX(), pinnedScroll + gap / 2f,
                math.dividerX(), pinnedScroll + 2 * math.tile + gap / 2f, separator);
    }

    @Override protected int getChildDrawingOrder(int count, int i) {
        // Draw the dragged tile last so it floats above its neighbours.
        int dragged = dragging == null ? -1 : indexOfChild(dragging);
        if (dragged < 0) return i;
        if (i == count - 1) return dragged;
        return i >= dragged ? i + 1 : i;
    }

    // ---- touch ----

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        touchX = ev.getX();
        touchY = ev.getY();
        if (dragging != null) {
            // The event that is intercepted never reaches onTouchEvent, so handle it here:
            // after a long-press the very next event may already be the finger lifting.
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE) updateDrag();
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) endDrag();
            return true;
        }
        if (!editing) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = touchX; downY = touchY;
                pressedInEdit = tileAt(touchX, touchY);
                if (pressedInEdit != null && pressedInEdit.spec.isStatus()) pressedInEdit = null;
                // In edit mode a touch on a tile is a drag, not a scroll.
                if (pressedInEdit != null) disallowParentIntercept();
                break;
            case MotionEvent.ACTION_MOVE:
                if (pressedInEdit != null && moved()) {
                    startDrag(pressedInEdit);
                    updateDrag();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressedInEdit = null;
                break;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        touchX = ev.getX();
        touchY = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Only reached when no tile took the touch: empty space.
                backgroundDown = true;
                downX = touchX; downY = touchY;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging != null) updateDrag();
                else if (backgroundDown && moved()) backgroundDown = false;
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging != null) endDrag();
                else if (backgroundDown && editing) setEditing(false);
                backgroundDown = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging != null) endDrag();
                backgroundDown = false;
                return true;
        }
        return true;
    }

    private boolean moved() {
        return Math.abs(touchX - downX) > slop || Math.abs(touchY - downY) > slop;
    }

    private TileView tileAt(float x, float y) {
        for (TileView tile : order) {
            float left = tile.getLeft() + tile.getTranslationX(), top = tile.getTop() + tile.getTranslationY();
            if (x >= left && x < left + tile.getWidth() && y >= top && y < top + tile.getHeight()) return tile;
        }
        return null;
    }

    private void disallowParentIntercept() {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
    }

    // ---- drag ----

    private void startDrag(TileView tile) {
        dragging = tile;
        pressedInEdit = null;
        tile.setPressed(false);
        tile.setRotation(0);
        grabX = touchX - (tile.getLeft() + tile.getTranslationX());
        grabY = touchY - (tile.getTop() + tile.getTranslationY());
        tile.animate().cancel();
        tile.animate().scaleX(1.07f).scaleY(1.07f).alpha(0.94f).setDuration(120).start();
        disallowParentIntercept();
        invalidate();
    }

    private void updateDrag() {
        if (dragging == null || math == null) return;
        float centerX = touchX - grabX + math.tile / 2f, centerY = touchY - grabY + math.tile / 2f;
        int from = order.indexOf(dragging);
        int to = math.indexAt(centerX, centerY, order.size());
        if (to >= 0 && to != from) {
            float[] oldX = new float[order.size()], oldY = new float[order.size()];
            List<TileView> before = new ArrayList<>(order);
            for (int i = 0; i < before.size(); i++) {
                oldX[i] = before.get(i).getLeft() + before.get(i).getTranslationX();
                oldY[i] = before.get(i).getTop() + before.get(i).getTranslationY();
            }
            order.add(to, order.remove(from));
            for (int i = 0; i < order.size(); i++) {
                TileView tile = order.get(i);
                if (tile == dragging) { place(tile, i); continue; }
                int was = before.indexOf(tile);
                place(tile, i);
                if (tile.getLeft() == oldX[was] && tile.getTop() == oldY[was]) continue;
                tile.animate().cancel();
                tile.setTranslationX(oldX[was] - tile.getLeft());
                tile.setTranslationY(oldY[was] - tile.getTop());
                tile.animate().translationX(0).translationY(0).setDuration(200).start();
            }
        }
        dragging.setTranslationX(touchX - grabX - dragging.getLeft());
        dragging.setTranslationY(touchY - grabY - dragging.getTop());
        autoScroll();
    }

    private void endDrag() {
        TileView tile = dragging;
        dragging = null;
        removeCallbacks(scrollStep);
        tile.animate().cancel();
        tile.animate().translationX(0).translationY(0).scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start();
        invalidate();
        if (getParent() instanceof Scroll) ((Scroll) getParent()).snap(0);
        if (listener != null) listener.onOrderChanged(new ArrayList<>(order));
    }

    private final Runnable scrollStep = new Runnable() {
        @Override public void run() { autoScroll(); }
    };

    /** While a dragged tile is held near the top or bottom edge, scroll toward it. */
    private void autoScroll() {
        removeCallbacks(scrollStep);
        if (dragging == null || !(getParent() instanceof ScrollView)) return;
        ScrollView scroll = (ScrollView) getParent();
        float y = touchY - scroll.getScrollY();
        int step = y < edge ? -gap : y > scroll.getHeight() - edge ? gap : 0;
        if (step == 0) return;
        int before = scroll.getScrollY();
        scroll.scrollBy(0, step);
        int delta = scroll.getScrollY() - before;
        if (delta == 0) return;
        touchY += delta; // the finger is still; the content moved under it
        updateDrag();
        postOnAnimation(scrollStep);
    }

    // ---- edit-mode wobble ----

    private void startWobble() {
        stopWobble();
        wobble = ValueAnimator.ofFloat(0f, 1f);
        wobble.setDuration(1000);
        wobble.setRepeatCount(ValueAnimator.INFINITE);
        wobble.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                double t = SystemClock.uptimeMillis() / 1000.0 * Math.PI * 2 * 3.2;
                for (int i = 0; i < order.size(); i++) {
                    TileView tile = order.get(i);
                    tile.setRotation(tile == dragging || tile.spec.isStatus() ? 0f : (float) (1.1 * Math.sin(t + i * 1.9)));
                }
            }
        });
        wobble.start();
    }

    private void stopWobble() {
        if (wobble != null) wobble.cancel();
        wobble = null;
        for (TileView tile : order) tile.setRotation(0f);
    }

    @Override protected void onDetachedFromWindow() {
        stopWobble();
        super.onDetachedFromWindow();
    }

    // ---- TUNE knob ----

    /** Distance between the tops of two rows, for scroll snapping. */
    int rowPitch() { return math == null ? 0 : math.tile + math.gap; }

    int knobIndex() { return knobIndex; }

    void setKnobIndex(int index) {
        knobIndex = index;
        applyKnob();
        if (index >= 0 && index < order.size() && !(math != null && math.grouped && index < fixedCount)) {
            TileView tile = order.get(index);
            tile.requestRectangleOnScreen(new Rect(0, 0, tile.getWidth(), tile.getHeight()), false);
        }
    }

    private void applyKnob() {
        for (int i = 0; i < order.size(); i++) order.get(i).setActivated(i == knobIndex);
    }

    /** Hosts the grid, tells it the visible height before measuring, and snaps to whole rows. */
    static final class Scroll extends ScrollView {
        private final TileGrid grid;
        private boolean flung;

        Scroll(Context context, TileGrid grid) {
            super(context);
            this.grid = grid;
            setFillViewport(true);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setClipChildren(false);
            addView(grid, new LayoutParams(-1, -2));
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            grid.setViewportHeight(MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom());
            super.onMeasure(widthSpec, heightSpec);
        }

        @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
            super.onScrollChanged(x, y, oldX, oldY);
            grid.setPinnedScroll(y);
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) flung = false;
            boolean handled = super.onTouchEvent(ev);
            // A fling already chose a row; a slow drag settles on the nearest one.
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !flung) snap(0);
            return handled;
        }

        /** A fling moves one row in its direction rather than coasting to an arbitrary offset. */
        @Override public void fling(int velocityY) {
            flung = true;
            snap(velocityY > 0 ? 1 : -1);
        }

        /** direction 0 snaps to the nearest row, otherwise one row down (1) or up (-1). */
        void snap(int direction) {
            int pitch = grid.rowPitch();
            if (pitch <= 0) return;
            int y = getScrollY();
            int row = direction > 0 ? y / pitch + 1 : direction < 0 ? (y + pitch - 1) / pitch - 1 : Math.round(y / (float) pitch);
            int max = Math.max(0, grid.getHeight() - (getHeight() - getPaddingTop() - getPaddingBottom()));
            smoothScrollTo(0, Math.max(0, Math.min(max, row * pitch)));
        }
    }
}
