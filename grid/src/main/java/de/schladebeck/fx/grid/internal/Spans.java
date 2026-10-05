package de.schladebeck.fx.grid.internal;

import java.util.Arrays;

/// Positions of a sequence of items, rows or columns, where most items share a default size. Only items with
/// another size are stored, sorted by index, together with the extra size before each of them. A hundred million
/// uniform items need no memory at all; finding the item at a position is a binary search over the exceptions.
///
/// Used for the vertical positions of view rows and the horizontal positions of visible view columns.
public final class Spans {

    private int count;
    private double defaultSize;
    private int[] indices = new int[0];
    private double[] sizes = new double[0];
    /** {@code extraBefore[i]} is the sum of {@code sizes[j] - defaultSize} for all {@code j < i}. */
    private double[] extraBefore = {0};

    public void setUniform(int count, double defaultSize) {
        set(count, defaultSize, new int[0], new double[0], 0);
    }

    /** {@code indices} ascending; the first {@code n} entries of {@code indices} and {@code sizes} are used. */
    public void set(int count, double defaultSize, int[] indices, double[] sizes, int n) {
        this.count = count;
        this.defaultSize = defaultSize;
        this.indices = Arrays.copyOf(indices, n);
        this.sizes = Arrays.copyOf(sizes, n);
        extraBefore = new double[n + 1];
        for (int i = 0; i < n; i++) {
            extraBefore[i + 1] = extraBefore[i] + sizes[i] - defaultSize;
        }
    }

    /// Changes the size of one item. Costs a pass over the stored items after it, not over all items, so updating a
    /// few rows of millions stays cheap.
    public void setSize(int index, double size) {
        int i = lowerBound(index);
        boolean stored = i < indices.length && indices[i] == index;
        if (size == (stored ? sizes[i] : defaultSize)) {
            return;
        }
        if (size == defaultSize) {
            indices = remove(indices, i);
            sizes = remove(sizes, i);
        } else if (stored) {
            sizes[i] = size;
        } else {
            indices = insert(indices, i, index);
            sizes = insert(sizes, i, size);
        }
        if (extraBefore.length != indices.length + 1) {
            extraBefore = Arrays.copyOf(extraBefore, indices.length + 1);
        }
        for (int j = i; j < indices.length; j++) {
            extraBefore[j + 1] = extraBefore[j] + sizes[j] - defaultSize;
        }
    }

    /// Items were inserted at `index` with the default size; the items from there on move along. Costs a pass
    /// over the stored items after it.
    public void inserted(int index, int n) {
        count += n;
        for (int i = lowerBound(index); i < indices.length; i++) {
            indices[i] += n;
        }
    }

    /// Items `index..index+n-1` were removed; the items after them move up. Costs a pass over the stored items
    /// after them.
    public void removed(int index, int n) {
        count -= n;
        int from = lowerBound(index);
        int to = lowerBound(index + n);
        if (to > from) {
            int kept = indices.length - (to - from);
            int[] keptIndices = Arrays.copyOf(indices, kept);
            double[] keptSizes = Arrays.copyOf(sizes, kept);
            System.arraycopy(indices, to, keptIndices, from, kept - from);
            System.arraycopy(sizes, to, keptSizes, from, kept - from);
            indices = keptIndices;
            sizes = keptSizes;
            extraBefore = Arrays.copyOf(extraBefore, kept + 1);
            for (int i = from; i < kept; i++) {
                extraBefore[i + 1] = extraBefore[i] + sizes[i] - defaultSize;
            }
        }
        for (int i = from; i < indices.length; i++) {
            indices[i] -= n;
        }
    }

    public int count() {
        return count;
    }

    public double defaultSize() {
        return defaultSize;
    }

    public double total() {
        return count * defaultSize + extraBefore[indices.length];
    }

    /** Start of an item relative to the first one; {@code start(count())} is the total size. */
    public double start(int index) {
        return index * defaultSize + extraBefore[lowerBound(index)];
    }

    public double size(int index) {
        int i = lowerBound(index);
        return i < indices.length && indices[i] == index ? sizes[i] : defaultSize;
    }

    /** The item at a position relative to the first item, or -1 outside all items. */
    public int indexAt(double position) {
        if (position < 0 || position >= total()) {
            return -1;
        }
        int found = -1;
        int lo = 0;
        int hi = indices.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (startOfStored(mid) <= position) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        double base = 0;
        int baseIndex = 0;
        if (found >= 0) {
            if (position < startOfStored(found) + sizes[found]) {
                return indices[found];
            }
            base = startOfStored(found) + sizes[found];
            baseIndex = indices[found] + 1;
        }
        return Math.min(count - 1, baseIndex + (int) Math.floor((position - base) / defaultSize));
    }

    private static int[] insert(int[] array, int at, int value) {
        int[] result = new int[array.length + 1];
        System.arraycopy(array, 0, result, 0, at);
        result[at] = value;
        System.arraycopy(array, at, result, at + 1, array.length - at);
        return result;
    }

    private static double[] insert(double[] array, int at, double value) {
        double[] result = new double[array.length + 1];
        System.arraycopy(array, 0, result, 0, at);
        result[at] = value;
        System.arraycopy(array, at, result, at + 1, array.length - at);
        return result;
    }

    private static int[] remove(int[] array, int at) {
        int[] result = new int[array.length - 1];
        System.arraycopy(array, 0, result, 0, at);
        System.arraycopy(array, at + 1, result, at, array.length - at - 1);
        return result;
    }

    private static double[] remove(double[] array, int at) {
        double[] result = new double[array.length - 1];
        System.arraycopy(array, 0, result, 0, at);
        System.arraycopy(array, at + 1, result, at, array.length - at - 1);
        return result;
    }

    /** Start of the {@code i}-th stored item. */
    private double startOfStored(int i) {
        return indices[i] * defaultSize + extraBefore[i];
    }

    /** Position of the first stored item at or after {@code index}. */
    private int lowerBound(int index) {
        int lo = 0;
        int hi = indices.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (indices[mid] < index) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
