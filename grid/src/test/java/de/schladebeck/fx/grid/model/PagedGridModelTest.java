package de.schladebeck.fx.grid.model;

import static de.schladebeck.fx.grid.model.PagedGridModel.CACHED_PAGES;
import static de.schladebeck.fx.grid.model.PagedGridModel.MAX_PARALLEL_LOADS;
import static de.schladebeck.fx.grid.model.PagedGridModel.MAX_QUEUED_PAGES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import de.schladebeck.fx.grid.Fx;
import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.PagedGridModel.PageRequest;
import de.schladebeck.fx.grid.model.PagedGridModel.SortColumn;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.IntStream;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PagedGridModelTest {

    /** Loads right away on the calling thread, so tests see the result immediately. */
    private static final Executor NOW = Runnable::run;

    private final List<PageRequest> requests = new ArrayList<>();

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    /** 1,000 numbers, sorted descending when asked to sort column 0 that way. */
    private PagedGridModel<Integer> numbers(Executor executor) {
        return new PagedGridModel<Integer>(1_000, 10, request -> {
            requests.add(request);
            return IntStream.range(request.offset(), request.offset() + request.count())
                    .map(i -> request.sortColumns().equals(List.of(new SortColumn(0, "Number", false))) ? 999 - i : i)
                    .boxed().toList();
        }, executor).column("Number", n -> n);
    }

    @Test
    void loadsThePagesThatAreAskedFor() throws Exception {
        Fx.run(() -> {
            PagedGridModel<Integer> model = numbers(NOW);

            assertEquals(123, model.getValueAt(123, 0));
            assertEquals(new PageRequest(120, 10, List.of()), requests.getLast());
            assertEquals(129, model.getValueAt(129, 0));
            assertEquals(1, requests.size(), "the page is cached");
            assertNull(model.getLoadedItem(500), "nothing else loaded");
            assertEquals(1, model.getLoadedPageCount());
        });
    }

    @Test
    void cellsAreEmptyUntilTheirPageArrives() throws Exception {
        List<Runnable> waiting = new ArrayList<>();
        Fx.run(() -> {
            PagedGridModel<Integer> model = numbers(waiting::add);
            List<Integer> updated = new ArrayList<>();
            model.addGridModelListener(e -> updated.add(e.firstRow()));

            assertNull(model.getValueAt(55, 0));
            assertNull(model.getValueAt(56, 0));
            assertEquals(1, waiting.size(), "one request per page");
            assertEquals(1, model.getPendingPageCount());

            waiting.getFirst().run();
            assertEquals(56, model.getValueAt(56, 0));
            assertEquals(List.of(50), updated, "an update for the loaded rows");
            assertEquals(0, model.getPendingPageCount());
        });
    }

    @Test
    void theGridSortsAtTheSource() throws Exception {
        Fx.run(() -> {
            PagedGridModel<Integer> model = numbers(NOW);
            Grid grid = Fx.show(new Grid(model));

            grid.sort(0, false);
            Fx.layout(grid);

            assertEquals(999, grid.getViewValueAt(0, 0));
            assertEquals(List.of(new SortKey(0, false)), grid.getSortKeys());
            assertTrue(requests.stream().allMatch(r -> r.offset() < 100 || r.sortColumns().isEmpty()),
                    "the grid never loads all rows to sort");
            assertEquals(List.of(new SortColumn(0, "Number", false)), requests.getLast().sortColumns());
            assertEquals(0, grid.viewToModelRow(0), "the grid itself keeps the model order");

            grid.clearSort();
            Fx.layout(grid);
            assertEquals(0, grid.getViewValueAt(0, 0));
        });
    }

    @Test
    void theLoaderGetsTheSortColumnByName() throws Exception {
        Fx.run(() -> {
            PagedGridModel<Integer> model = numbers(NOW);

            model.sortRows(List.of(new SortKey(0, false)), column -> null);
            model.getValueAt(0, 0);

            assertEquals(new PageRequest(0, 10, List.of(new SortColumn(0, "Number", false))), requests.getLast());
        });
    }

    @Test
    void failedPagesAreReportedOnceAndReloadTriesAgain() throws Exception {
        Fx.run(() -> {
            int[] attempts = {0};
            List<Throwable> errors = new ArrayList<>();
            PagedGridModel<Integer> model = new PagedGridModel<Integer>(100, 10, request -> {
                if (attempts[0]++ == 0) {
                    throw new IllegalStateException("database down");
                }
                return IntStream.range(request.offset(), request.offset() + request.count()).boxed().toList();
            }, NOW).column("Number", n -> n);
            model.setOnLoadError(errors::add);

            assertNull(model.getValueAt(3, 0));
            assertNull(model.getValueAt(3, 0));
            assertEquals(1, attempts[0], "a failed page is not retried on every paint");
            assertEquals("database down", errors.getFirst().getMessage());

            model.reload(100);
            assertEquals(3, model.getValueAt(3, 0));
        });
    }

    @Test
    void aRejectedLoadIsReportedOnceAndReloadTriesAgain() throws Exception {
        Fx.run(() -> {
            boolean[] reject = {true};
            int[] executions = {0};
            List<Throwable> errors = new ArrayList<>();
            PagedGridModel<Integer> model = numbers(command -> {
                executions[0]++;
                if (reject[0]) {
                    throw new RejectedExecutionException("shut down");
                }
                command.run();
            });
            model.setOnLoadError(errors::add);

            assertNull(model.getValueAt(3, 0));
            assertNull(model.getValueAt(3, 0));
            assertEquals(1, executions[0], "a rejected page is not retried on every paint");
            assertEquals(0, model.getPendingPageCount());
            assertEquals("shut down", errors.getFirst().getMessage());

            reject[0] = false;
            model.reload();
            assertEquals(3, model.getValueAt(3, 0));
        });
    }

    @Test
    void aThrowingErrorHandlerDoesNotStopQueuedLoads() throws Exception {
        List<Runnable> waiting = new ArrayList<>();
        Fx.run(() -> {
            PagedGridModel<Integer> model = new PagedGridModel<Integer>(1_000, 10, request -> {
                if (request.offset() == 0) {
                    throw new IllegalStateException("database down");
                }
                return IntStream.range(request.offset(), request.offset() + request.count()).boxed().toList();
            }, waiting::add).column("Number", n -> n);
            model.setOnLoadError(error -> {
                throw new IllegalArgumentException("a bug in the handler");
            });
            for (int page = 0; page <= MAX_PARALLEL_LOADS; page++) {
                model.getValueAt(page * 10, 0);
            }
            assertEquals(MAX_PARALLEL_LOADS, waiting.size(), "one page waits for a free slot");

            waiting.getFirst().run();
            assertEquals(MAX_PARALLEL_LOADS + 1, waiting.size(), "the waiting page starts");
        });
    }

    @Test
    void aReloadWhileAPageLoadsRequestsItAgain() throws Exception {
        List<Runnable> waiting = new ArrayList<>();
        Fx.run(() -> {
            PagedGridModel<Integer> model = numbers(waiting::add);

            assertNull(model.getValueAt(0, 0));
            model.reload();
            assertNull(model.getValueAt(0, 0));
            assertEquals(2, waiting.size(), "the load for the old data does not block the new request");

            waiting.getFirst().run();
            assertNull(model.getLoadedItem(0), "the stale page is dropped");
            assertEquals(1, model.getPendingPageCount());
            waiting.getLast().run();
            assertEquals(0, model.getValueAt(0, 0));
        });
    }

    @Test
    void loadsForAnOlderSortOrderDoNotTakeTheSlots() throws Exception {
        List<Runnable> waiting = new ArrayList<>();
        Fx.run(() -> {
            PagedGridModel<Integer> model = numbers(waiting::add);
            for (int page = 0; page < MAX_PARALLEL_LOADS; page++) {
                model.getValueAt(page * 10, 0);
            }

            model.sortRows(List.of(new SortKey(0, false)), column -> null);
            for (int page = 0; page < MAX_PARALLEL_LOADS; page++) {
                model.getValueAt(page * 10, 0);
            }
            assertEquals(2 * MAX_PARALLEL_LOADS, waiting.size(), "the visible pages load again in the new order");

            waiting.reversed().forEach(Runnable::run);
            assertEquals(999, model.getValueAt(0, 0), "pages in the old order arriving late are dropped");
        });
    }

    @Test
    void theNewestRequestLoadsFirst() throws Exception {
        List<Runnable> waiting = new ArrayList<>();
        Fx.run(() -> {
            PagedGridModel<Integer> model = numbers(waiting::add);
            for (int page = 0; page < MAX_PARALLEL_LOADS + 3; page++) {
                model.getValueAt(page * 10, 0);
            }
            model.getValueAt(MAX_PARALLEL_LOADS * 10, 0); // asked for again, it is the newest request now

            runAll(waiting);

            assertEquals(List.of(40, 60, 50), requests.subList(MAX_PARALLEL_LOADS, requests.size()).stream()
                    .map(PageRequest::offset).toList());
        });
    }

    @Test
    void requestsWaitingBeyondTheLimitAreDroppedOldestFirstAndLoadWhenAskedForAgain() throws Exception {
        List<Runnable> waiting = new ArrayList<>();
        Fx.run(() -> {
            PagedGridModel<Integer> model = numbers(waiting::add);
            for (int page = 0; page < MAX_PARALLEL_LOADS + MAX_QUEUED_PAGES + 2; page++) {
                model.getValueAt(page * 10, 0);
            }
            assertEquals(MAX_PARALLEL_LOADS + MAX_QUEUED_PAGES, model.getPendingPageCount());

            runAll(waiting);
            assertEquals(MAX_PARALLEL_LOADS + MAX_QUEUED_PAGES, requests.size());
            assertNull(model.getLoadedItem(40), "the oldest waiting page is dropped");
            assertNull(model.getLoadedItem(50), "and the next oldest");
            assertEquals(60, model.getLoadedItem(60));

            assertNull(model.getValueAt(40, 0));
            runAll(waiting);
            assertEquals(40, model.getValueAt(40, 0), "a dropped page loads when it is asked for again");
        });
    }

    @Test
    void sortingOrReloadingDropsTheWaitingRequests() throws Exception {
        List<Runnable> waiting = new ArrayList<>();
        Fx.run(() -> {
            PagedGridModel<Integer> model = numbers(waiting::add);
            for (int page = 0; page < MAX_PARALLEL_LOADS + 4; page++) {
                model.getValueAt(page * 10, 0);
            }
            model.sortRows(List.of(new SortKey(0, false)), column -> null);
            assertEquals(0, model.getPendingPageCount());
            runAll(waiting);

            for (int page = 0; page < MAX_PARALLEL_LOADS + 4; page++) {
                model.getValueAt(page * 10, 0);
            }
            model.reload();
            assertEquals(0, model.getPendingPageCount());
            runAll(waiting);

            assertEquals(2 * MAX_PARALLEL_LOADS, requests.size(), "only the loads already started ran");
            assertTrue(requests.stream().allMatch(r -> r.offset() < MAX_PARALLEL_LOADS * 10), requests.toString());
            assertEquals(0, model.getLoadedPageCount());
        });
    }

    /** Runs the waiting loads, and the loads they start when they arrive, until none is left. */
    private static void runAll(List<Runnable> waiting) {
        for (int i = 0; i < waiting.size(); i++) {
            waiting.get(i).run();
        }
        waiting.clear();
    }

    @Test
    void pagesOnScreenStayEvenIfTheyAreMoreThanTheCacheHolds() throws Exception {
        List<Runnable> waiting = new ArrayList<>();
        Fx.run(() -> {
            int visible = CACHED_PAGES + 32;
            PagedGridModel<Integer> model = new PagedGridModel<Integer>(1_000, 1, request -> {
                requests.add(request);
                return List.of(request.offset());
            }, waiting::add).column("Number", n -> n);

            paintUntilLoaded(model, waiting, 0, visible);
            assertEquals(visible, requests.size(), "each page loads once");
            assertEquals(visible, model.getLoadedPageCount());

            paintUntilLoaded(model, waiting, 500, 10);
            assertEquals(CACHED_PAGES, model.getLoadedPageCount(), "the cache shrinks again");
        });
    }

    /** Paints rows {@code first..first+count-1} and lets the loads arrive between paints until none is pending. */
    private static void paintUntilLoaded(PagedGridModel<?> model, List<Runnable> waiting, int first, int count) {
        for (int paint = 0; paint < 100; paint++) {
            for (int row = first; row < first + count; row++) {
                model.getValueAt(row, 0);
            }
            if (model.getPendingPageCount() == 0) {
                return;
            }
            List<Runnable> arrived = List.copyOf(waiting);
            waiting.clear();
            arrived.forEach(Runnable::run);
        }
        fail("still loading after 100 paints");
    }

    @Test
    void columnsCanBeAddedFromAnyThread() throws Exception {
        PagedGridModel<Integer> model = new PagedGridModel<>(1_000, 10, request -> List.of(), NOW);
        List<Boolean> onFxThread = new ArrayList<>();
        model.addGridModelListener(e -> onFxThread.add(Platform.isFxApplicationThread()));

        model.column("Number", n -> n).rowNames(String::valueOf);

        Fx.run(() -> {
            assertEquals(1, model.getColumnCount());
            assertEquals(List.of(true, true), onFxThread, "the STRUCTURE events fire on the JavaFX thread");
        });
    }
}
