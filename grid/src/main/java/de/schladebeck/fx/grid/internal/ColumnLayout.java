package de.schladebeck.fx.grid.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/// Column order, visibility, widths and width limits, the column counterpart of [ViewRows] and the row [Spans].
/// Built to cost nothing for columns that keep their defaults, so a model with 100 million columns stays cheap:
///
/// - Widths: a default width, plus a map of the columns with their own width. Positions of the visible columns
///   come from [Spans], which stores only the exceptions.
/// - Width limits: defaults, plus a map of the columns with their own limits.
/// - Visibility: hidden columns as a sorted array.
/// - Order: runs of consecutive model columns in view order, hidden columns included. Natural order is the one
///   run from 0 to `modelCount - 1`. Moving a column splits at most two runs and inserts one; runs that become
///   consecutive again are merged. Each run stores its first model column, its offset in the full order and the
///   visible columns before it, so view and model indices are converted by binary searches over the runs and the
///   hidden columns.
///
/// Memory and the cost of a change grow with the number of runs, hidden columns and own widths, not with the
/// column count. Methods take view columns unless the name says model.
public final class ColumnLayout {

    private int modelCount;
    private double defaultWidth;
    private double defaultMinWidth;
    private final Map<Integer, Double> widths = new HashMap<>();
    private final Map<Integer, double[]> limits = new HashMap<>();
    private int[] hidden = new int[0];
    /** First model column of each run, in view order. */
    private int[] runStart = new int[0];
    /** Offset of each run in the full order, hidden columns included; the last entry is the model count. */
    private int[] runOffset = {0};
    /** Visible columns before each run; the last entry is the visible count. */
    private int[] visibleBefore = {0};
    /** Run indices sorted by first model column. */
    private int[] byStart = new int[0];
    private final Spans spans = new Spans();

    public void resize(int modelCount, double defaultWidth) {
        resize(modelCount, defaultWidth, 0);
    }

    /// Changes the model column count. Existing columns keep width, limits, position and visibility; new columns
    /// get the defaults and are appended.
    public void resize(int modelCount, double defaultWidth, double defaultMinWidth) {
        int old = this.modelCount;
        this.modelCount = modelCount;
        this.defaultWidth = defaultWidth;
        this.defaultMinWidth = defaultMinWidth;
        widths.keySet().removeIf(column -> column >= modelCount);
        limits.keySet().removeIf(column -> column >= modelCount);
        hidden = Arrays.stream(hidden).filter(column -> column < modelCount).toArray();
        RunBuilder kept = new RunBuilder(runStart.length + 1);
        for (int run = 0; run < runStart.length; run++) {
            kept.add(runStart[run], Math.min(runOffset[run + 1] - runOffset[run], modelCount - runStart[run]));
        }
        kept.add(old, modelCount - old);
        apply(kept);
    }

    /** Natural order, all columns visible, default widths and limits. The column count stays. */
    public void clear() {
        widths.clear();
        limits.clear();
        hidden = new int[0];
        setNaturalOrder();
    }

    /** Visible columns. */
    public int count() {
        return visibleBefore[runStart.length];
    }

    public int modelCount() {
        return modelCount;
    }

    public int toModel(int viewColumn) {
        int run = Math.max(0, lastAtMost(visibleBefore, runStart.length, viewColumn));
        int start = runStart[run];
        int k = viewColumn - visibleBefore[run];
        // The model column is start + k plus the hidden columns from start up to it: the number of j >= h with
        // hidden[j] - (j - h) <= start + k, which grows with j.
        int h = hiddenBefore(start);
        int lo = h;
        int hi = hidden.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (hidden[mid] - (mid - h) <= start + k) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return start + k + lo - h;
    }

    /** View column of a model column, or -1 if it is hidden or no model column. */
    public int toView(int modelColumn) {
        if (modelColumn < 0 || modelColumn >= modelCount || !isVisible(modelColumn)) {
            return -1;
        }
        int run = runOf(modelColumn);
        int start = runStart[run];
        return visibleBefore[run] + modelColumn - start - (hiddenBefore(modelColumn) - hiddenBefore(start));
    }

    boolean isNaturalOrder() {
        return runStart.length <= 1;
    }

    /** All model columns in view order, hidden ones included. Costs an array of all columns. */
    public int[] order() {
        int[] order = new int[modelCount];
        for (int run = 0; run < runStart.length; run++) {
            for (int i = runOffset[run]; i < runOffset[run + 1]; i++) {
                order[i] = runStart[run] + i - runOffset[run];
            }
        }
        return order;
    }

    /// Sets the order of all model columns, hidden ones included. Must be a permutation; natural order costs
    /// nothing.
    void setOrder(int[] newOrder) {
        if (newOrder.length != modelCount) {
            throw new IllegalArgumentException("Expected " + modelCount + " columns, got " + newOrder.length);
        }
        RunBuilder runs = new RunBuilder(16);
        for (int column : newOrder) {
            runs.add(column, 1);
        }
        apply(runs);
    }

    /** The order as {first, last} model column ranges in view order, hidden ones included; empty for natural order. */
    public List<int[]> runs() {
        List<int[]> runs = new ArrayList<>();
        if (!isNaturalOrder()) {
            for (int run = 0; run < runStart.length; run++) {
                runs.add(new int[] {runStart[run], runStart[run] + runOffset[run + 1] - runOffset[run] - 1});
            }
        }
        return runs;
    }

    /// Sets the order as {first, last} model column ranges in view order, hidden columns included, as [#runs()]
    /// returns them. The ranges must cover every model column exactly once; an empty list means natural order.
    public void setRuns(List<int[]> runs) {
        if (runs.isEmpty()) {
            setNaturalOrder();
            return;
        }
        RunBuilder builder = new RunBuilder(runs.size());
        for (int[] run : runs) {
            if (run.length != 2 || run[0] < 0 || run[0] > run[1] || run[1] >= modelCount) {
                throw new IllegalArgumentException("Not a column range: " + Arrays.toString(run));
            }
            builder.add(run[0], run[1] - run[0] + 1);
        }
        apply(builder);
    }

    void setNaturalOrder() {
        RunBuilder natural = new RunBuilder(1);
        natural.add(0, modelCount);
        apply(natural);
    }

    public boolean isVisible(int modelColumn) {
        return Arrays.binarySearch(hidden, modelColumn) < 0;
    }

    public void setVisible(int modelColumn, boolean visible) {
        if (isVisible(modelColumn) == visible) {
            return;
        }
        hidden = visible ? Arrays.stream(hidden).filter(column -> column != modelColumn).toArray()
                : IntStream.concat(Arrays.stream(hidden), IntStream.of(modelColumn)).sorted().toArray();
        rebuild();
    }

    /** Hides exactly these model columns. */
    public void setHidden(Collection<Integer> columns) {
        hidden = columns.stream().mapToInt(Integer::intValue).filter(c -> c >= 0 && c < modelCount)
                .distinct().sorted().toArray();
        rebuild();
    }

    /** The hidden model columns, ascending. */
    public int[] hidden() {
        return hidden.clone();
    }

    /** Moves the visible column at {@code fromView} so that it ends up at visible position {@code toView}. */
    public void move(int fromView, int toView) {
        if (fromView == toView) {
            return;
        }
        int from = offset(toModel(fromView));
        int to = offset(toModel(toView));
        RunBuilder moved = new RunBuilder(runStart.length + 3);
        if (from < to) {
            addOffsets(moved, 0, from);
            addOffsets(moved, from + 1, to + 1);
            addOffsets(moved, from, from + 1);
            addOffsets(moved, to + 1, modelCount);
        } else {
            addOffsets(moved, 0, to);
            addOffsets(moved, from, from + 1);
            addOffsets(moved, to, from);
            addOffsets(moved, from + 1, modelCount);
        }
        apply(moved);
    }

    public double width(int viewColumn) {
        return modelWidth(toModel(viewColumn));
    }

    public double modelWidth(int modelColumn) {
        return widths.getOrDefault(modelColumn, defaultWidth);
    }

    public void setModelWidth(int modelColumn, double width) {
        setModelWidths(Map.of(modelColumn, width));
    }

    /** Sets the widths of many model columns with one rebuild of the positions. */
    public void setModelWidths(Map<Integer, Double> newWidths) {
        newWidths.forEach((column, width) -> {
            if (width == defaultWidth) {
                widths.remove(column);
            } else {
                widths.put(column, width);
            }
        });
        rebuildSpans();
    }

    public double minWidth(int modelColumn) {
        double[] limit = limits.get(modelColumn);
        return limit == null ? defaultMinWidth : limit[0];
    }

    public double maxWidth(int modelColumn) {
        double[] limit = limits.get(modelColumn);
        return limit == null ? Double.MAX_VALUE : limit[1];
    }

    /** Sets the width limits of a model column; the width itself is the caller's business. */
    public void setLimits(int modelColumn, double min, double max) {
        limits.put(modelColumn, new double[] {min, Math.max(min, max)});
    }

    public double start(int viewColumn) {
        return spans.start(viewColumn);
    }

    public double total() {
        return spans.total();
    }

    /** View column containing position {@code x}, or -1 if outside all columns. */
    public int columnAt(double x) {
        return spans.indexAt(x);
    }

    /** Replaces the order with {@code runs} after checking that they cover every model column exactly once. */
    private void apply(RunBuilder runs) {
        int n = runs.count;
        long[] keys = new long[n];
        for (int run = 0; run < n; run++) {
            keys[run] = (long) runs.start[run] << 32 | run;
        }
        Arrays.sort(keys);
        int[] sorted = new int[n];
        int next = 0;
        int i = 0;
        while (i < n && runs.start[(int) keys[i]] == next) {
            int run = (int) keys[i];
            next += runs.offset[run + 1] - runs.offset[run];
            sorted[i++] = run;
        }
        if (i < n || next != modelCount) {
            throw new IllegalArgumentException("Each model column from 0 to " + (modelCount - 1)
                    + " must appear exactly once");
        }
        runStart = Arrays.copyOf(runs.start, n);
        runOffset = Arrays.copyOf(runs.offset, n + 1);
        byStart = sorted;
        rebuild();
    }

    /** Adds the columns at full order offsets {@code from} (inclusive) to {@code end} (exclusive). */
    private void addOffsets(RunBuilder to, int from, int end) {
        for (int run = lastAtMost(runOffset, runStart.length, from); from < end; run++) {
            int next = Math.min(end, runOffset[run + 1]);
            to.add(runStart[run] + from - runOffset[run], next - from);
            from = next;
        }
    }

    /** Offset of a model column in the full order, hidden columns included. */
    private int offset(int modelColumn) {
        int run = runOf(modelColumn);
        return runOffset[run] + modelColumn - runStart[run];
    }

    /** The run containing a model column. */
    private int runOf(int modelColumn) {
        int lo = 0;
        int hi = byStart.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (runStart[byStart[mid]] <= modelColumn) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return byStart[lo - 1];
    }

    /** Hidden model columns below {@code modelColumn}. */
    private int hiddenBefore(int modelColumn) {
        int i = Arrays.binarySearch(hidden, modelColumn);
        return i >= 0 ? i : -i - 1;
    }

    /** Last {@code i < n} with {@code values[i] <= key}, or -1; {@code values} ascending. */
    private static int lastAtMost(int[] values, int n, int key) {
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (values[mid] <= key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo - 1;
    }

    /** Visible columns per run, then positions; after the order or the hidden columns changed. */
    private void rebuild() {
        int n = runStart.length;
        visibleBefore = new int[n + 1];
        for (int run = 0; run < n; run++) {
            int start = runStart[run];
            int end = start + runOffset[run + 1] - runOffset[run];
            visibleBefore[run + 1] = visibleBefore[run] + end - start - (hiddenBefore(end) - hiddenBefore(start));
        }
        rebuildSpans();
    }

    /** Positions: only visible columns with their own width are exceptions. */
    private void rebuildSpans() {
        long[] custom = new long[widths.size()];
        int n = 0;
        for (Integer column : widths.keySet()) {
            int view = toView(column);
            if (view >= 0) {
                custom[n++] = (long) view << 32 | column;
            }
        }
        Arrays.sort(custom, 0, n);
        int[] views = new int[n];
        double[] sizes = new double[n];
        for (int i = 0; i < n; i++) {
            views[i] = (int) (custom[i] >>> 32);
            sizes[i] = widths.get((int) custom[i]);
        }
        spans.set(count(), defaultWidth, views, sizes, n);
    }

    /** Runs in view order as they are added; a run that continues the previous one is merged into it. */
    private static final class RunBuilder {

        private int[] start;
        /** Offset of each run in the full order; one entry more than runs. */
        private int[] offset;
        private int count;

        RunBuilder(int capacity) {
            start = new int[capacity];
            offset = new int[capacity + 1];
        }

        void add(int first, int length) {
            if (length <= 0) {
                return;
            }
            if (count > 0 && start[count - 1] + offset[count] - offset[count - 1] == first) {
                offset[count] += length;
                return;
            }
            if (count == start.length) {
                start = Arrays.copyOf(start, 2 * count + 1);
                offset = Arrays.copyOf(offset, 2 * count + 2);
            }
            start[count] = first;
            offset[count + 1] = offset[count] + length;
            count++;
        }
    }
}
