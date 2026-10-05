package de.schladebeck.fx.grid.internal;

import de.schladebeck.fx.grid.internal.RowSorting.Key;
import de.schladebeck.fx.grid.model.GridModel;
import de.schladebeck.fx.grid.model.SortKey;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/// Maps view rows to model rows: an optional filter, then an optional stable sort ([RowSorting]), like Swing's
/// `RowSorter`. The mapping is an [IndexMap]; without filter and sort it is the identity and holds no arrays.
/// [ColumnLayout] does the same for columns.
///
/// Rebuilt lazily on first access after a change, so a burst of changes costs one rebuild. Inserted and deleted
/// rows update a built mapping in place ([#rowsInserted], [#rowsDeleted]), which assumes that the filter and the
/// sort look at values, not at row numbers. Values that change later do not move rows or re-run the filter until
/// the next rebuild.
public final class ViewRows {

    private GridModel model;
    private IntPredicate filter;
    /** The sort keys, the first one decides; empty for the model order. */
    private List<Key> sortKeys = List.of();
    /** The model sorted its own rows, see {@link GridModel#sortRows}; the mapping stays the identity. */
    private boolean modelSorts;
    private final IndexMap map = new IndexMap();
    private boolean dirty = true;
    private int version;
    /** Lookups by model row since the mapping last changed, see {@link #toView}. */
    private int lookups;
    private Consumer<RuntimeException> onError = e -> {
        throw e;
    };

    /** Shows another model, unsorted. A model that sorted itself returns to its natural order. */
    public void setModel(GridModel model) {
        clearSort();
        this.model = model;
        invalidate();
    }

    /// Gets what a filter or a comparator throws. The rows are then shown unfiltered and unsorted until they or
    /// the filter or sort change, so a broken filter can still be replaced.
    public void setOnError(Consumer<RuntimeException> onError) {
        this.onError = onError;
    }

    public void setFilter(IntPredicate filter) {
        this.filter = filter;
        invalidate();
    }

    /** Sorts by one column in the default order, see {@link #sort(List, IntFunction)}. */
    public void sort(int modelColumn, boolean ascending) {
        sort(modelColumn, ascending, null);
    }

    /** Sorts by one column, see {@link #sort(List, IntFunction)}. */
    public void sort(int modelColumn, boolean ascending, Comparator<Object> comparator) {
        sort(List.of(new SortKey(modelColumn, ascending)), column -> comparator);
    }

    /// Sorts by model columns, stable: the first key decides, the next ones break its ties, the model order breaks
    /// the rest. The model does it if it can ([GridModel#sortRows]). Otherwise `comparators` gives each column's
    /// order of non-null values, nulls first; `null` means [RowSorting#DEFAULT_ORDER]. No keys show the model
    /// order; a model that sorted itself returns to its natural order. Returns whether the model sorted its rows.
    public boolean sort(List<SortKey> keys, IntFunction<Comparator<Object>> comparators) {
        boolean modelSorted = modelSorts;
        sortKeys = keys.stream().map(key -> Key.of(key, comparators.apply(key.column()))).toList();
        // sortRows fires an event: listeners that read the rows meanwhile must not start a sort of all rows
        modelSorts = true;
        invalidate();
        boolean sorted = model != null && !keys.isEmpty() && model.sortRows(List.copyOf(keys), this::valueOrder);
        if (!sorted && modelSorted) {
            model.sortRows(List.of(), this::valueOrder);
        }
        modelSorts = sorted;
        invalidate();
        return sorted;
    }

    /** Shows the rows in model order. A model that sorted itself returns to its natural order. */
    public void clearSort() {
        sort(List.of(), column -> null);
    }

    /** Rows were added, removed or reordered: rebuild before the next access. */
    public void invalidate() {
        dirty = true;
    }

    /// Model rows `first` to `first + count - 1` were inserted. A built mapping takes the ones the filter accepts
    /// where a rebuild would put them, found by binary search; otherwise it is rebuilt on the next access.
    public void rowsInserted(int first, int count) {
        if (!updatable(count)) {
            invalidate();
            return;
        }
        dirty = true; // until done, so a filter or sort that throws leaves a rebuild
        try {
            insert(first, count);
        } catch (RuntimeException e) {
            onError.accept(e);
            return;
        }
        dirty = false;
        changed();
    }

    private void insert(int first, int count) {
        map.modelInserted(first, count);
        int[] added = new int[count];
        int k = 0;
        for (int row = first; row < first + count; row++) {
            if (filter == null || filter.test(row)) {
                added[k++] = row;
            }
        }
        added = Arrays.copyOf(added, k);
        boolean byValue = sortsLocally();
        RowSorting.mergeSort(added, (a, b) -> compareRows(byValue, a, b));
        int[] before = new int[k];
        int lo = 0;
        for (int i = 0; i < k; i++) {
            int hi = map.count();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (compareRows(byValue, map.toModel(mid), added[i]) < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            before[i] = lo;
        }
        map.insert(before, added);
    }

    /** Model rows {@code first} to {@code first + count - 1} were deleted. */
    public void rowsDeleted(int first, int count) {
        if (updatable(-count)) {
            map.modelRemoved(first, count);
            changed();
        } else {
            invalidate();
        }
    }

    /// Whether the mapping is up to date. While it is not, the next [#count], [#toModel] or [#toView] rebuilds it,
    /// which can mean reading and sorting every row; callers that only want to know whether something is worth a
    /// repaint ask this first.
    public boolean isBuilt() {
        return !dirty;
    }

    public boolean isIdentity() {
        return filter == null && (sortKeys.isEmpty() || modelSorts);
    }

    /** The sort keys, the first one decides; empty when the rows are in model order. */
    public List<SortKey> sortKeys() {
        return sortKeys.stream().map(key -> new SortKey(key.column(), key.ascending())).toList();
    }

    public int count() {
        ensureBuilt();
        return map.count();
    }

    public int toModel(int viewRow) {
        ensureBuilt();
        return map.toModel(viewRow);
    }

    /** Counts changes of the mapping, so dependent data (row heights) knows when the view rows changed. */
    public int version() {
        ensureBuilt();
        return version;
    }

    /// View row of a model row, or -1 if filtered out.
    ///
    /// The inverse mapping costs a pass over all rows after every change. A few lookups after a change, such as
    /// the top row on screen or a selected row during a live feed, search the view instead: it is ordered by model
    /// row, or by value and then model row. A search reads about `2 log2(n)` values, so after `n / 1024` lookups
    /// the inverse is cheaper. A row whose sort value changed since it was placed is not found by the search and
    /// is looked up in the inverse.
    public int toView(int modelRow) {
        ensureBuilt();
        if (map.isIdentity() || map.hasInverse() || lookups++ >= map.count() >> 10) {
            return map.toView(modelRow);
        }
        int view = search(modelRow);
        return view >= 0 || !sortsLocally() ? view : map.toView(modelRow);
    }

    /** Binary search for a model row in the built, non-identity view; -1 if not there. */
    private int search(int modelRow) {
        if (modelRow >= model.getRowCount()) {
            return -1;
        }
        Object[] values = sortsLocally() ? valuesOf(modelRow) : null;
        int lo = 0;
        int hi = map.count() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int row = map.toModel(mid);
            int c = values == null ? 0 : compareValues(row, values);
            c = c != 0 ? c : Integer.compare(row, modelRow);
            if (c == 0) {
                return mid;
            } else if (c < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return -1;
    }

    /** The ascending order of a sort column's values, for a model that sorts itself. */
    private Comparator<Object> valueOrder(int column) {
        return sortKeys.stream().filter(key -> key.column() == column).map(Key::values).findFirst()
                .orElse(RowSorting.DEFAULT_ORDER);
    }

    private boolean sortsLocally() {
        return !sortKeys.isEmpty() && !modelSorts;
    }

    /** The values of a model row in the sort columns. */
    private Object[] valuesOf(int modelRow) {
        Object[] values = new Object[sortKeys.size()];
        Arrays.setAll(values, i -> model.getValueAt(modelRow, sortKeys.get(i).column()));
        return values;
    }

    /** View order of a model row against values of the sort columns. */
    private int compareValues(int modelRow, Object[] values) {
        for (int i = 0; i < values.length; i++) {
            Key key = sortKeys.get(i);
            int c = key.view().compare(model.getValueAt(modelRow, key.column()), values[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Whether a built mapping can follow a change of {@code delta} rows: not the identity, in step with the model. */
    private boolean updatable(int delta) {
        return !dirty && !map.isIdentity() && map.modelCount() + delta == model.getRowCount();
    }

    private void ensureBuilt() {
        if (!dirty) {
            return;
        }
        if (model != null) {
            int columns = model.getColumnCount();
            sortKeys = sortKeys.stream().filter(key -> key.column() < columns).toList();
        }
        if (model == null || isIdentity()) {
            map.setIdentity(model == null ? 0 : model.getRowCount());
        } else {
            try {
                map.set(filteredAndSorted(), model.getRowCount());
            } catch (RuntimeException e) {
                onError.accept(e);
                map.setIdentity(model.getRowCount());
            }
        }
        dirty = false;
        changed();
    }

    private int[] filteredAndSorted() {
        int n = model.getRowCount();
        int[] rows = new int[n];
        int count = 0;
        for (int row = 0; row < n; row++) {
            if (filter == null || filter.test(row)) {
                rows[count++] = row;
            }
        }
        if (count < n) {
            rows = Arrays.copyOf(rows, count);
        }
        return sortsLocally() ? RowSorting.sorted(model, sortKeys, rows) : rows;
    }

    private void changed() {
        version++;
        lookups = 0;
    }

    /** View order of two model rows: by the sort keys if {@code byValue}, then by model row like a stable sort. */
    private int compareRows(boolean byValue, int a, int b) {
        int c = byValue ? compareValues(a, valuesOf(b)) : 0;
        return c != 0 ? c : Integer.compare(a, b);
    }
}
