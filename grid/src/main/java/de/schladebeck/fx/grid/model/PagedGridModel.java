package de.schladebeck.fx.grid.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

/// A model for data that is too large or too slow to load at once, such as a database table or a remote service.
/// Rows are loaded page by page on a background thread when the grid first shows them:
///
/// ```java
/// PagedGridModel<Person> people = new PagedGridModel<Person>(repository.count(),
///         page -> repository.find(page.offset(), page.count(), page.sortColumns()))
///         .column("Name", Person::name)
///         .column("City", Person::city);
/// Grid grid = new Grid(people);
/// ```
///
/// - Cells of rows that are still loading are empty (`null`) and fill in when their page arrives. Only the rows on
///   screen are repainted then.
/// - Pages are loaded newest request first, at most 4 at a time, so fast scrolling loads what is on screen now.
///   Requests waiting beyond 16 are dropped, oldest first. A loader call that never returns keeps one of the 4
///   slots busy until the next reload or sort.
/// - The 64 most recently used pages stay in memory, more while the grid shows more pages at once.
/// - Clicking a column header sorts at the source: the grid calls [#sortRows], the cache is dropped and the loader
///   gets the new order in its [PageRequest], one or more columns. The grid never reads all rows to sort them.
/// - Filtering belongs to the source as well: change what the loader queries and call [#reload(int)] with the new
///   row count. Grid row filters would read every row and should not be used with this model.
/// - Columns are read-only; edit in a form and write to the source, then [#reload()].
///
/// @param <T> the type of the items
public class PagedGridModel<T> extends AbstractGridModel {

    /// What the loader should load.
    ///
    /// @param offset      first row
    /// @param count       number of rows; the last page may be shorter
    /// @param sortColumns the columns to sort by, like the columns of an `ORDER BY` clause; empty for the natural
    ///                    order
    public record PageRequest(int offset, int count, List<SortColumn> sortColumns) {
    }

    /// One column of a [PageRequest]'s sort order.
    ///
    /// @param column    the model column
    /// @param name      its name, see [PagedGridModel#getColumnName]
    /// @param ascending the direction
    public record SortColumn(int column, String name, boolean ascending) {
    }

    /** Loads one page of items, on a background thread. */
    @FunctionalInterface
    public interface PageLoader<T> {

        /// Loads the requested rows.
        ///
        /// @param request which rows, in which order
        /// @return the items, at most `request.count()`
        /// @throws Exception if loading fails; the model reports it to its error handler
        List<T> load(PageRequest request) throws Exception;
    }

    /// At most this many pages load at the same time. Loads made stale by a reload or sort no longer count, so
    /// for a moment more loader calls than this may run.
    static final int MAX_PARALLEL_LOADS = 4;
    /** Requests waiting beyond this many are dropped, oldest first. */
    static final int MAX_QUEUED_PAGES = 16;
    /** This many most recently used pages stay in memory; more while the grid shows more at once. */
    static final int CACHED_PAGES = 64;
    /** Rows per page unless set otherwise. */
    static final int DEFAULT_PAGE_SIZE = 100;

    private static final Executor VIRTUAL_THREADS = Executors.newVirtualThreadPerTaskExecutor();
    private static final System.Logger LOG = System.getLogger(PagedGridModel.class.getName());

    private final ItemColumns<T> columns = new ItemColumns<>();
    private final PageLoader<T> loader;
    private final int pageSize;
    private final Executor executor;
    /** Loaded pages, least recently used first. */
    private final Map<Integer, List<T>> pages = new LinkedHashMap<>(16, 0.75f, true);
    /// Pages read since a page last arrived, and pages that arrived since the last read. They are on screen, so
    /// arriving pages do not evict them.
    private final Set<Integer> inUse = new HashSet<>();
    /** A page arrived since the last read; the next read starts a new {@link #inUse}. */
    private boolean arrivedSinceRead;
    private final Deque<Integer> queue = new ArrayDeque<>();
    /** Pages loading for the current generation; older loads are forgotten when the generation changes. */
    private final Set<Integer> loading = new HashSet<>();
    private final Set<Integer> failed = new HashSet<>();
    private int rowCount;
    private List<SortColumn> sortColumns = List.of();
    /** Counts reloads, so pages loaded for an older query are dropped. */
    private int generation;
    private Consumer<Throwable> onLoadError = error ->
            LOG.log(System.Logger.Level.WARNING, "Loading a page failed", error);

    /// A model with 100 rows per page, loading on virtual threads.
    ///
    /// @param rowCount number of rows at the source
    /// @param loader   loads one page
    public PagedGridModel(int rowCount, PageLoader<T> loader) {
        this(rowCount, DEFAULT_PAGE_SIZE, loader, VIRTUAL_THREADS);
    }

    /// A model with its own page size and executor for the loader.
    ///
    /// @param rowCount number of rows at the source
    /// @param pageSize rows per page
    /// @param loader   loads one page
    /// @param executor runs the loader
    public PagedGridModel(int rowCount, int pageSize, PageLoader<T> loader, Executor executor) {
        this.rowCount = rowCount;
        this.pageSize = pageSize;
        this.loader = loader;
        this.executor = executor;
    }

    /// A model with one column per record component, see [ListGridModel#ofRecords(Class)].
    ///
    /// @param type     the record class
    /// @param rowCount number of rows at the source
    /// @param loader   loads one page
    /// @param <R>      the record type
    /// @return the model
    public static <R extends Record> PagedGridModel<R> ofRecords(Class<R> type, int rowCount, PageLoader<R> loader) {
        PagedGridModel<R> model = new PagedGridModel<>(rowCount, loader);
        model.columns.addRecordComponents(type);
        return model;
    }

    /** Adds a column. Returns this model for chaining. Any thread. */
    public <V> PagedGridModel<T> column(String name, Function<? super T, ? extends V> getter) {
        applySetup(() -> {
            columns.add(name, null, getter, null);
            fireStructureChanged();
        });
        return this;
    }

    /** Row header text per item instead of row numbers; rows still loading show their number. Any thread. */
    public PagedGridModel<T> rowNames(Function<? super T, String> names) {
        applySetup(() -> {
            columns.setRowNames(names);
            fireStructureChanged();
        });
        return this;
    }

    /// Receives errors from the loader, or from the executor rejecting a load, instead of the default log message.
    /// Called on the JavaFX application thread. The failed page stays empty until [#reload()].
    public void setOnLoadError(Consumer<Throwable> handler) {
        onLoadError = handler;
    }

    /// Drops all loaded pages and loads again what is on screen, e.g. after the data at the source changed or the
    /// loader's query got another filter. Any thread.
    ///
    /// @param newRowCount the row count of the new data
    public void reload(int newRowCount) {
        applyChange(() -> {
            rowCount = newRowCount;
            dropPages();
            fireDataChanged();
        });
    }

    /** {@link #reload(int)} with the current row count. Any thread. */
    public void reload() {
        applyChange(() -> {
            dropPages();
            fireDataChanged();
        });
    }

    /** The item in a model row, or {@code null} while its page is loading. Does not start loading. */
    public T getLoadedItem(int modelRow) {
        List<T> page = pages.get(modelRow / pageSize);
        if (page == null) {
            return null;
        }
        if (arrivedSinceRead) {
            inUse.clear();
            arrivedSinceRead = false;
        }
        inUse.add(modelRow / pageSize);
        int index = modelRow % pageSize;
        return index < page.size() ? page.get(index) : null;
    }

    /** Pages in memory. */
    public int getLoadedPageCount() {
        return pages.size();
    }

    /** Pages loading or waiting to load, not counting loads made stale by a reload or sort. */
    public int getPendingPageCount() {
        return loading.size() + queue.size();
    }

    // ---- GridModel

    @Override
    public int getRowCount() {
        return rowCount;
    }

    @Override
    public int getColumnCount() {
        return columns.count();
    }

    @Override
    public Object getValueAt(int row, int column) {
        return columns.value(item(row), column);
    }

    @Override
    public String getColumnName(int column) {
        return columns.name(column);
    }

    @Override
    public String getRowName(int row) {
        return columns.rowName(getLoadedItem(row));
    }

    /** The declared type for records, otherwise the class of the first loaded non-null value in the first 10 rows. */
    @Override
    public Class<?> getColumnClass(int column) {
        return columns.type(column, this::getLoadedItem, rowCount);
    }

    /** Sorts at the source: drops the loaded pages and lets the loader load in the new order. */
    @Override
    public boolean sortRows(List<SortKey> keys, IntFunction<Comparator<Object>> order) {
        sortColumns = keys.stream()
                .map(key -> new SortColumn(key.column(), columns.name(key.column()), key.ascending())).toList();
        dropPages();
        fireDataChanged();
        return true;
    }

    // ---- loading, all on the JavaFX application thread except the loader itself

    /** The item in a model row; requests its page if it is not loaded. */
    private T item(int row) {
        int page = row / pageSize;
        if (!pages.containsKey(page) && !failed.contains(page)) {
            request(page);
        }
        return getLoadedItem(row);
    }

    private void request(int page) {
        if (loading.contains(page)) {
            return;
        }
        queue.remove(page);
        queue.addFirst(page);
        while (queue.size() > MAX_QUEUED_PAGES) {
            queue.removeLast();
        }
        startLoads();
    }

    private void startLoads() {
        while (loading.size() < MAX_PARALLEL_LOADS && !queue.isEmpty()) {
            int page = queue.removeFirst();
            if (pages.containsKey(page)) {
                continue;
            }
            loading.add(page);
            int forGeneration = generation;
            int offset = page * pageSize;
            PageRequest request = new PageRequest(offset, Math.min(pageSize, rowCount - offset), sortColumns);
            try {
                executor.execute(() -> {
                    List<T> items = null;
                    Throwable error = null;
                    try {
                        items = new ArrayList<>(loader.load(request));
                    } catch (Throwable e) {
                        error = e;
                    }
                    List<T> loaded = items;
                    Throwable failure = error;
                    applyChange(() -> pageLoaded(page, forGeneration, loaded, failure));
                });
            } catch (RejectedExecutionException e) {
                loading.remove(page);
                fail(page, e);
            }
        }
    }

    private void pageLoaded(int page, int forGeneration, List<T> items, Throwable error) {
        if (forGeneration != generation) {
            return;
        }
        loading.remove(page);
        if (error != null) {
            fail(page, error);
        } else {
            pages.put(page, items);
            inUse.add(page);
            arrivedSinceRead = true;
            evictUnusedPages();
            int first = page * pageSize;
            int last = Math.min(rowCount, first + items.size()) - 1;
            if (last >= first) {
                fireRowsUpdated(first, last);
            }
        }
        startLoads();
    }

    /** Evicts the least recently used pages beyond {@link #CACHED_PAGES}, except those in use. */
    private void evictUnusedPages() {
        Iterator<Integer> eldest = pages.keySet().iterator();
        while (pages.size() > CACHED_PAGES && eldest.hasNext()) {
            if (!inUse.contains(eldest.next())) {
                eldest.remove();
            }
        }
    }

    /** Marks the page failed until the next reload and reports the error; a throwing handler is only logged. */
    private void fail(int page, Throwable error) {
        failed.add(page);
        try {
            onLoadError.accept(error);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "The load error handler failed", e);
        }
    }

    private void dropPages() {
        generation++;
        pages.clear();
        queue.clear();
        loading.clear();
        failed.clear();
    }
}
