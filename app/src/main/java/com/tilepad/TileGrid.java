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
import android.widget.HorizontalScrollView;

import java.util.ArrayList;
import java.util.List;

/**
 * Square tiles on horizontal pages sized to the visible area, inside a paging {@link Scroll}.
 * Page one starts with the fixed core block; more user tiles than fit there continue on later
 * pages, with dots at the bottom. Long-press a tile to enter edit mode and drag it; in edit mode
 * any movable tile drags right away. Tapping empty space, or {@link #setEditing(false)}, leaves
 * edit mode.
 */
final class TileGrid extends ViewGroup {
    interface Listener {
        void onEditingChanged(boolean editing);
        void onOrderChanged(List<TileView> order);
    }

    private final List<TileView> order = new ArrayList<>();
    private final int gap, minTile, maxTile, slop, edge;
    private Listener listener;
    private PagedGridMath math;
    private int fixedCount;
    private final Paint separator = new Paint();
    private int viewportWidth, viewportHeight, stride, knobIndex = -1;
    /** Height kept free under the tiles for the page dots, when there is more than one page. */
    final int dotsBand;
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
        dotsBand = ui.dp(28);
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
        // Keep the fixed block predictable while preserving the relative order
        // of every other saved tile and shortcut.
        for (TileSpec.Kind kind : TileSpec.FIXED)
            for (TileView tile : tiles) if (tile.spec.kind == kind) order.add(tile);
        fixedCount = order.size();
        for (TileView tile : tiles) if (!tile.spec.isFixed()) order.add(tile);
        for (TileView tile : order) {
            addView(tile);
            tile.setEditing(editing);
        }
        if (knobIndex >= order.size()) knobIndex = order.size() - 1;
        applyKnob();
    }

    List<TileView> tiles() { return order; }

    /** The visible page size tiles are sized against; set by {@link Scroll}. */
    void setViewport(int width, int height, int pageStride) {
        if (width == viewportWidth && height == viewportHeight && pageStride == stride) return;
        viewportWidth = width; viewportHeight = height; stride = pageStride;
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
        if (tile.spec.isFixed()) return;
        if (dragging == null) startDrag(tile);
    }

    // ---- layout ----

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = viewportWidth > 0 ? viewportWidth : MeasureSpec.getSize(widthSpec);
        int height = viewportHeight > 0 ? viewportHeight : MeasureSpec.getSize(heightSpec);
        math = PagedGridMath.compute(width, height, gap, minTile, maxTile, fixedCount, stride);
        if (math.pages(order.size()) > 1) math = PagedGridMath.compute(width, height - dotsBand, gap, minTile, maxTile, fixedCount, stride);
        int exact = MeasureSpec.makeMeasureSpec(math.tile, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(exact, exact);
        setMeasuredDimension(math.contentWidth(order.size()), height);
    }

    int pages() { return math == null ? 1 : math.pages(order.size()); }
    /** Scroll distance between pages: the page plus the host's side padding. */
    int pageStride() { return math == null ? 0 : math.stride; }
    int pageOf(int index) { return math == null ? 0 : math.page(index); }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < order.size(); i++) place(order.get(i), i);
    }

    private void place(View view, int index) {
        int left = math.left(index), top = math.top(index);
        view.layout(left, top, left + math.tile, top + math.tile);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // The divider belongs to page one, beside the fixed block.
        if (math != null && math.grouped && order.size() > fixedCount)
            canvas.drawLine(math.dividerX(), gap / 2f, math.dividerX(), math.contentHeight() - gap / 2f, separator);
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
                if (pressedInEdit != null && pressedInEdit.spec.isFixed()) pressedInEdit = null;
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

    /** While a dragged tile is held near the left or right edge, slide toward the next page. */
    private void autoScroll() {
        removeCallbacks(scrollStep);
        if (dragging == null || !(getParent() instanceof HorizontalScrollView)) return;
        HorizontalScrollView scroll = (HorizontalScrollView) getParent();
        float x = touchX - scroll.getScrollX() + scroll.getPaddingLeft();
        int step = x < edge ? -2 * gap : x > scroll.getWidth() - edge ? 2 * gap : 0;
        if (step == 0) return;
        int before = scroll.getScrollX();
        scroll.scrollBy(step, 0);
        int delta = scroll.getScrollX() - before;
        if (delta == 0) return;
        touchX += delta; // the finger is still; the content moved under it
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
                    tile.setRotation(tile == dragging || tile.spec.isFixed() ? 0f : (float) (1.1 * Math.sin(t + i * 1.9)));
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

    int knobIndex() { return knobIndex; }

    void setKnobIndex(int index) {
        knobIndex = index;
        applyKnob();
        if (index >= 0 && index < order.size() && getParent() instanceof Scroll) ((Scroll) getParent()).showPage(pageOf(index));
    }

    private void applyKnob() {
        for (int i = 0; i < order.size(); i++) order.get(i).setActivated(i == knobIndex);
    }

    /** Hosts the grid, tells it the page size before measuring, snaps to whole pages and draws the page dots. */
    static final class Scroll extends HorizontalScrollView {
        private final TileGrid grid;
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int radius, spacing;
        private boolean flung;

        Scroll(Context context, TileGrid grid, Ui ui) {
            super(context);
            this.grid = grid;
            radius = ui.dp(4); spacing = ui.dp(18);
            setFillViewport(true);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setClipChildren(false);
            setWillNotDraw(false);
            addView(grid, new LayoutParams(-2, -1));
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            grid.setViewport(width - getPaddingLeft() - getPaddingRight(),
                MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom(), width);
            super.onMeasure(widthSpec, heightSpec);
        }

        @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
            super.onScrollChanged(x, y, oldX, oldY);
            invalidate();  // the dots follow the page
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            // Fewer pages after a removal: settle on the last page that still exists. Other layouts
            // (status tiles update their text every second) must not disturb a swipe in progress.
            int last = (grid.pages() - 1) * grid.pageStride();
            if (getScrollX() > last) scrollTo(last, 0);
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) flung = false;
            boolean handled = super.onTouchEvent(ev);
            // A fling already chose a page; a slow drag settles on the nearest one.
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !flung) snap(0);
            return handled;
        }

        /** A fling moves one page in its direction rather than coasting to an arbitrary offset. */
        @Override public void fling(int velocityX) {
            flung = true;
            snap(velocityX > 0 ? 1 : -1);
        }

        int currentPage() {
            int width = grid.pageStride();
            return width <= 0 ? 0 : Math.round(getScrollX() / (float) width);
        }

        /** direction 0 snaps to the nearest page, otherwise one page right (1) or left (-1). */
        void snap(int direction) {
            int width = grid.pageStride();
            if (width <= 0) return;
            int x = getScrollX();
            int page = direction > 0 ? x / width + 1 : direction < 0 ? (x + width - 1) / width - 1 : Math.round(x / (float) width);
            showPage(page);
        }

        void showPage(int page) {
            int width = grid.pageStride();
            if (width <= 0) return;
            smoothScrollTo(Math.max(0, Math.min(grid.pages() - 1, page)) * width, 0);
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            int pages = grid.pages();
            if (pages < 2) return;
            float cx = getScrollX() + getWidth() / 2f - (pages - 1) * spacing / 2f;
            float cy = getPaddingTop() + grid.getHeight() - grid.dotsBand / 2f;
            int current = currentPage();
            for (int i = 0; i < pages; i++) {
                dot.setColor(i == current ? Ui.ACCENT : Ui.LINE);
                canvas.drawCircle(cx + i * spacing, cy, radius, dot);
            }
        }
    }
}
