package com.tilepad;

/** A fixed two-by-two status block and a separate row-major shortcut area. */
final class GroupedGridMath {
    final int tile, gap, fixedCount, userCols, userLeft, offsetX;
    final boolean grouped;
    private final GridMath fallback;

    private GroupedGridMath(int tile, int gap, int fixedCount, int userCols, int userLeft, int offsetX, GridMath fallback) {
        this.tile = tile; this.gap = gap; this.fixedCount = fixedCount;
        this.userCols = userCols; this.userLeft = userLeft; this.offsetX = offsetX;
        this.fallback = fallback; grouped = fallback == null;
    }

    static GroupedGridMath compute(int width, int height, int gap, int minTile, int maxTile, int fixedCount) {
        int dividerExtra = gap;
        if (fixedCount == 0 || width < 3 * minTile + 2 * gap + dividerExtra || height < 2 * minTile + gap) {
            GridMath old = GridMath.compute(width, height, gap, minTile, maxTile);
            return new GroupedGridMath(old.tile, gap, fixedCount, old.cols, 0, old.offsetX, old);
        }
        int tile = Math.max(1, Math.min(maxTile, (height - gap) / 2));
        int cols = Math.max(3, Math.round((width - dividerExtra + gap) / (float) (tile + gap)));
        tile = Math.min(tile, (width - (cols - 1) * gap - dividerExtra) / cols);
        int used = cols * tile + (cols - 1) * gap + dividerExtra;
        int offset = Math.max(0, (width - used) / 2);
        return new GroupedGridMath(tile, gap, fixedCount, cols - 2,
            offset + 2 * (tile + gap) + dividerExtra, offset, null);
    }
    int left(int index) {
        if (!grouped) return fallback.left(index);
        return index < fixedCount ? offsetX + index % 2 * (tile + gap)
            : userLeft + (index - fixedCount) % userCols * (tile + gap);
    }
    int top(int index) {
        if (!grouped) return fallback.top(index);
        return (index < fixedCount ? index / 2 : (index - fixedCount) / userCols) * (tile + gap);
    }
    int contentHeight(int count) {
        if (!grouped) return fallback.contentHeight(count);
        int userRows = (Math.max(0, count - fixedCount) + userCols - 1) / userCols;
        int rows = Math.max(2, userRows);
        return rows * tile + (rows - 1) * gap;
    }
    /** Drag targets are always in the user-managed area, even when dragged over the status block. */
    int indexAt(float x, float y, int count) {
        if (count <= fixedCount) return -1;
        if (!grouped) return Math.max(fixedCount, fallback.indexAt(x, y, count));
        int col = Math.max(0, Math.min(userCols - 1, Math.round((x - userLeft - tile / 2f) / (tile + gap))));
        int row = Math.max(0, (int) Math.floor((y + gap / 2f) / (tile + gap)));
        return Math.min(count - 1, fixedCount + row * userCols + col);
    }
    float dividerX() { return (offsetX + 2 * tile + gap + userLeft) / 2f; }
}
