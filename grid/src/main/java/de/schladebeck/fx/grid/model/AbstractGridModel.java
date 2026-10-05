package de.schladebeck.fx.grid.model;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;

/// Base class for models: listener handling, fire helpers like Swing's `AbstractTableModel`, and a safe
/// way to change data from any thread.
///
/// The grid reads the model on the JavaFX application thread, so the data must only change there. Wrap
/// changes in [#applyChange], which runs them there:
/// ```java
/// public void put(int row, int column, double value) {    // any thread
///     applyChange(() -> {
///         values[row][column] = value;
///         fireRowsUpdated(row, row);
///     });
/// }
/// ```
/// Fire events only inside such changes or on the JavaFX thread; the grid rejects events from other threads.
public abstract class AbstractGridModel implements GridModel {

    /** How long queued changes may run in one go before input and painting get a turn. */
    private static final long BATCH_NANOS = 8_000_000;

    private final List<GridModelListener> listeners = new CopyOnWriteArrayList<>();
    private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();

    /** For subclasses. */
    protected AbstractGridModel() {
    }

    @Override
    public void addGridModelListener(GridModelListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeGridModelListener(GridModelListener listener) {
        listeners.remove(listener);
    }

    /// Runs a change of the model's data on the JavaFX application thread: right away when called there,
    /// otherwise queued. Change the data and fire the matching events inside `change`.
    ///
    /// - Changes from other threads run in the order they were applied. A change applied on the JavaFX thread
    ///   runs right away, on the rows as they are on screen, before changes still queued from other threads; so
    ///   row numbers taken from the grid stay right.
    /// - Queued changes run in batches of about 8 ms, so input and painting go on while other threads change
    ///   the model faster than the JavaFX thread can keep up.
    /// - A queued change that throws is passed to the uncaught exception handler of the thread running it,
    ///   and the changes after it still run.
    /// - Before the JavaFX toolkit has started, changes run right away on the calling thread, e.g. to fill a
    ///   model in `main` before `Application.launch`.
    ///
    /// @param change the data change, including the events it fires
    protected final void applyChange(Runnable change) {
        if (Platform.isFxApplicationThread()) {
            change.run();
        } else {
            pending.add(change);
            if (drainScheduled.compareAndSet(false, true)) {
                try {
                    Platform.runLater(this::runBatch);
                } catch (IllegalStateException toolkitNotStarted) {
                    drainScheduled.set(false);
                    runQueued(Integer.MAX_VALUE, Long.MAX_VALUE);
                }
            }
        }
    }

    /// For builder methods that set up columns, names or types: runs right away while no grid listens to the
    /// model, so a model can be set up and read back on any thread. Once a grid shows the model, the same as
    /// [#applyChange]. Changes of the data always use [#applyChange].
    ///
    /// @param change the change, including the events it fires
    protected final void applySetup(Runnable change) {
        if (listeners.isEmpty() && pending.isEmpty() && !Platform.isFxApplicationThread()) {
            change.run();
        } else {
            applyChange(change);
        }
    }

    /** Fires an {@code INSERT} event for model rows {@code firstRow..lastRow}. */
    protected void fireRowsInserted(int firstRow, int lastRow) {
        fire(GridModelEvent.Type.INSERT, firstRow, lastRow);
    }

    /** Fires a {@code DELETE} event for model rows {@code firstRow..lastRow}. */
    protected void fireRowsDeleted(int firstRow, int lastRow) {
        fire(GridModelEvent.Type.DELETE, firstRow, lastRow);
    }

    /** Fires an {@code UPDATE} event for model rows {@code firstRow..lastRow}. */
    protected void fireRowsUpdated(int firstRow, int lastRow) {
        fire(GridModelEvent.Type.UPDATE, firstRow, lastRow);
    }

    /** Fires a {@code DATA} event: all rows may have changed, the columns did not. */
    protected void fireDataChanged() {
        fire(GridModelEvent.Type.DATA, 0, Integer.MAX_VALUE);
    }

    /** Fires a {@code STRUCTURE} event: anything may have changed, including the columns. */
    protected void fireStructureChanged() {
        fire(GridModelEvent.Type.STRUCTURE, 0, Integer.MAX_VALUE);
    }

    private void fire(GridModelEvent.Type type, int firstRow, int lastRow) {
        if (listeners.isEmpty()) {
            return;
        }
        GridModelEvent event = new GridModelEvent(this, type, firstRow, lastRow);
        for (GridModelListener listener : listeners) {
            listener.gridChanged(event);
        }
    }

    /** Runs queued changes for up to {@link #BATCH_NANOS} on the JavaFX thread and schedules the rest. */
    private void runBatch() {
        drainScheduled.set(false);
        runQueued(Integer.MAX_VALUE, BATCH_NANOS);
        if (!pending.isEmpty() && drainScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::runBatch);
        }
    }

    /// Runs up to `count` queued changes and stops early when `budgetNanos` have passed. A change that throws
    /// goes to the current thread's uncaught exception handler, and the next one runs.
    private void runQueued(int count, long budgetNanos) {
        long start = System.nanoTime();
        for (int i = 0; i < count && System.nanoTime() - start < budgetNanos; i++) {
            Runnable change = pending.poll();
            if (change == null) {
                return;
            }
            try {
                change.run();
            } catch (Throwable e) {
                Thread thread = Thread.currentThread();
                thread.getUncaughtExceptionHandler().uncaughtException(thread, e);
            }
        }
    }
}
