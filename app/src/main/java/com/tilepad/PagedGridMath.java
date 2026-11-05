package com.tilepad;

/**
 * Horizontal pages of square tiles. Page one holds the fixed core block, row-major in
 * ceil(fixed / 2) columns over two rows, then a divider and the first user tiles; later pages
 * hold only user tiles, row-major. Tiles keep one size and column count on every page.
 * A viewport too small for the block falls back to plain {@link GridMath} rows on each page.
 * Pages are {@code stride} apart, at least the page width: the host's side padding is part of
 * the stride, so a neighbouring page never shows in it.
 */
final class PagedGridMath {
    final int tile, gap, rows, cols, fixedCount, fixedCols, pageWidth, stride;
    final boolean grouped;
    private final int firstLeft, laterLeft, divider;

    private PagedGridMath(int tile, int gap, int rows, int cols, int fixedCount, int fixedCols, int pageWidth, int stride,
                          int firstLeft, int laterLeft, int divider) {
        this.tile = tile; this.gap = gap; this.rows = rows; this.cols = cols; this.fixedCount = fixedCount;
        this.fixedCols = fixedCols; this.pageWidth = pageWidth; this.stride = Math.max(pageWidth, stride);
        this.firstLeft = firstLeft; this.laterLeft = laterLeft;
        this.divider = divider; grouped = fixedCols > 0;
    }

    static PagedGridMath compute(int width, int height, int gap, int minTile, int maxTile, int fixedCount) {
        return compute(width, height, gap, minTile, maxTile, fixedCount, width);
    }

    static PagedGridMath compute(int width, int height, int gap, int minTile, int maxTile, int fixedCount, int stride) {
        width = Math.max(1, width); height = Math.max(1, height);
        int fixedCols = (fixedCount + 1) / 2, divider = gap;
        if (fixedCount == 0 || width < (fixedCols + 1) * minTile + fixedCols * gap + divider || height < 2 * minTile + gap) {
            GridMath plain = GridMath.compute(width, height, gap, minTile, maxTile);
            return new PagedGridMath(plain.tile, gap, plain.rows, plain.cols, fixedCount, 0, width, stride, plain.offsetX, plain.offsetX, 0);
        }
        int tile = Math.max(1, Math.min(maxTile, (height - gap) / 2));
        int cols = Math.max(fixedCols + 1, Math.round((width - divider + gap) / (float) (tile + gap)));
        tile = Math.min(tile, (width - (cols - 1) * gap - divider) / cols);
        int span = cols * tile + (cols - 1) * gap;
        return new PagedGridMath(tile, gap, 2, cols, fixedCount, fixedCols, width, stride,
            Math.max(0, (width - span - divider) / 2), Math.max(0, (width - span) / 2), divider);
    }

    /** User tiles that fit beside the fixed block, and on each later page. */
    int firstCapacity() { return (cols - fixedCols) * rows - (grouped ? 0 : fixedCount); }
    int pageCapacity() { return cols * rows; }

    int pages(int count) {
        int user = Math.max(0, count - fixedCount), extra = user - firstCapacity();
        return extra <= 0 ? 1 : 1 + (extra + pageCapacity() - 1) / pageCapacity();
    }

    int page(int index) {
        int user = index - fixedCount;
        if (user < firstCapacity()) return 0;
        return 1 + (user - firstCapacity()) / pageCapacity();
    }

    int left(int index) {
        int page = page(index);
        if (page > 0) return page * stride + laterLeft + laterSlot(index) % cols * (tile + gap);
        if (!grouped) return firstLeft + index % cols * (tile + gap);
        if (index < fixedCount) return firstLeft + index % fixedCols * (tile + gap);
        return userLeft() + (index - fixedCount) % (cols - fixedCols) * (tile + gap);
    }

    int top(int index) {
        int page = page(index), row;
        if (page > 0) row = laterSlot(index) / cols;
        else if (!grouped) row = index / cols;
        else if (index < fixedCount) row = index / fixedCols;
        else row = (index - fixedCount) / (cols - fixedCols);
        return row * (tile + gap);
    }

    int contentWidth(int count) { return (pages(count) - 1) * stride + pageWidth; }
    int contentHeight() { return rows * tile + (rows - 1) * gap; }

    /** Drop targets are always user slots, so a tile dragged over the fixed block lands beside it. */
    int indexAt(float x, float y, int count) {
        if (count <= fixedCount) return -1;
        int page = Math.max(0, Math.min(pages(count) - 1, (int) Math.floor((x + (stride - pageWidth) / 2f) / stride)));
        float local = x - page * stride;
        int row = Math.max(0, Math.min(rows - 1, (int) Math.floor((y + gap / 2f) / (tile + gap))));
        int index;
        if (page > 0) {
            int col = column(local - laterLeft, cols);
            index = fixedCount + firstCapacity() + (page - 1) * pageCapacity() + row * cols + col;
        } else if (grouped) {
            index = fixedCount + row * (cols - fixedCols) + column(local - userLeft(), cols - fixedCols);
        } else {
            index = Math.max(fixedCount, row * cols + column(local - firstLeft, cols));
        }
        return Math.min(count - 1, index);
    }

    /** Halfway between the fixed block and the first user column, on page one. */
    float dividerX() { return firstLeft + fixedCols * tile + (fixedCols - 1) * gap + (gap + divider) / 2f; }

    private int userLeft() { return firstLeft + fixedCols * (tile + gap) + divider; }
    private int laterSlot(int index) { return (index - fixedCount - firstCapacity()) % pageCapacity(); }
    private int column(float x, int columns) {
        return Math.max(0, Math.min(columns - 1, Math.round((x - tile / 2f) / (tile + gap))));
    }
}
