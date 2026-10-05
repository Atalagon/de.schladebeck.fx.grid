package de.schladebeck.fx.grid.model;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.WeakListChangeListener;

/// A model for a list of objects, one object per row and one column per getter.
///
/// ```java
/// ListGridModel<Person> people = new ListGridModel<Person>()
///         .column("Name", Person::name)
///         .column("Salary", Person::salary);
/// people.setAll(loadPeople());
/// ```
///
/// Changes ([#setAll], [#add(Object)], [#set], [#remove], ...) and the builders ([#column], [#rowNames]) may come
/// from any thread. Changes from other threads are queued and applied on the JavaFX application thread in order,
/// see [AbstractGridModel#applyChange]. Reading methods ([#getItem], [#getItems()], ...) belong to the JavaFX
/// application thread; a background thread keeps its own copy of what it needs.
///
/// To show an `ObservableList` that other code changes, use [#of]: the grid then follows every change of it.
///
/// @param <T> the type of the items
public class ListGridModel<T> extends AbstractGridModel {

    private final ItemColumns<T> columns = new ItemColumns<>();
    /** The rows as the grid reads them. */
    private final List<T> items = new ArrayList<>();
    private final List<T> readOnlyItems = Collections.unmodifiableList(items);
    /// What the changing methods change: `items` itself, firing the events, or the list shown with [#of], whose
    /// listener copies each change into `items` and fires the events.
    private final List<T> list;
    private final ListChangeListener<T> listener = this::listChanged;

    /** An empty model without columns; add them with {@link #column}. */
    public ListGridModel() {
        list = items;
    }

    private ListGridModel(ObservableList<T> list) {
        this.list = list;
        items.addAll(list);
        list.addListener(new WeakListChangeListener<>(listener));
    }

    /// A model showing `list`, which keeps the items: every change of the list reaches the grid without further
    /// calls, and the changing methods of the model, as well as editing in the grid, change the list.
    ///
    /// ```java
    /// ObservableList<Person> people = FXCollections.observableArrayList(p -> new Observable[] {p.nameProperty()});
    /// Grid grid = new Grid(ListGridModel.of(people).column("Name", Person::getName));
    /// people.add(new Person("Ann"));          // inserts a row
    /// people.getFirst().setName("Bea");       // repaints it, reported by the extractor
    /// FXCollections.sort(people, byName);     // shows the new order
    /// ```
    ///
    /// - Added and removed items insert and delete rows, so the selection and the visible rows follow them as with
    ///   [#add(Object)] and [#remove]. `set` updates a row; `setAll` and a new order (`sort`) change all rows.
    /// - Changes inside an item reach the grid if the list reports them: create it with an extractor, as above.
    /// - Like any list shown in a JavaFX control, change the list on the JavaFX application thread only, and call
    ///   this method there or before the list is in use. The model's changing methods may still be called from any
    ///   thread: they change the list on the JavaFX application thread, see [AbstractGridModel#applyChange].
    /// - The list holds the model weakly, so a list that lives on does not keep the model or its grid alive. The
    ///   model follows the list for as long as something else holds it, such as the grid showing it.
    ///
    /// @param list the items, one per row; modifiable if the model's changing methods or editing are used
    /// @param <T>  the type of the items
    /// @return a model without columns; add them with [#column]
    public static <T> ListGridModel<T> of(ObservableList<T> list) {
        return new ListGridModel<>(list);
    }

    /// A model with one column per record component, titled from the component names. The record must be public
    /// and in a package exported to module `de.schladebeck.fx.grid`, or on the class path.
    ///
    /// @param type the record class
    /// @param <R>  the record type
    /// @return a model without items
    /// @throws IllegalArgumentException if the record is not accessible to module `de.schladebeck.fx.grid`
    public static <R extends Record> ListGridModel<R> ofRecords(Class<R> type) {
        ListGridModel<R> model = new ListGridModel<>();
        model.columns.addRecordComponents(type);
        return model;
    }

    /** {@link #ofRecords(Class)} filled with {@code items}. */
    public static <R extends Record> ListGridModel<R> ofRecords(Class<R> type, Collection<? extends R> items) {
        ListGridModel<R> model = ofRecords(type);
        model.setAll(items);
        return model;
    }

    /** Adds a read-only column. Returns this model for chaining. Any thread. */
    public <V> ListGridModel<T> column(String name, Function<? super T, ? extends V> getter) {
        applySetup(() -> {
            columns.add(name, null, getter, null);
            fireStructureChanged();
        });
        return this;
    }

    /// Adds a column that an editable grid can edit in place. `updater` gets the item and the new value and returns
    /// the item to store: the same object after changing it, or a new one for immutable types. Any thread.
    ///
    /// @param name    header text
    /// @param getter  the column's value of an item
    /// @param updater the item with a new value
    /// @param <V>     the type of the column's values
    /// @return this model, for chaining
    public <V> ListGridModel<T> column(String name, Function<? super T, ? extends V> getter,
                                       BiFunction<? super T, ? super V, ? extends T> updater) {
        applySetup(() -> {
            columns.add(name, null, getter, updater);
            fireStructureChanged();
        });
        return this;
    }

    /** Row header text per item, e.g. {@code Person::name}, instead of row numbers. Returns this model. Any thread. */
    public ListGridModel<T> rowNames(Function<? super T, String> names) {
        applySetup(() -> {
            columns.setRowNames(names);
            fireStructureChanged();
        });
        return this;
    }

    // ---- reading, JavaFX application thread

    /** The item shown in a model row. */
    public T getItem(int modelRow) {
        return items.get(modelRow);
    }

    /** The items at the given model rows, e.g. {@code getItems(grid.getSelectedModelRows())}. */
    public List<T> getItems(int[] modelRows) {
        List<T> result = new ArrayList<>(modelRows.length);
        for (int row : modelRows) {
            result.add(items.get(row));
        }
        return result;
    }

    /** All items, as a read-only live view. */
    public List<T> getItems() {
        return readOnlyItems;
    }

    /** A row filter for {@link de.schladebeck.fx.grid.Grid#setRowFilter} from a condition on the items. */
    public IntPredicate matching(Predicate<? super T> condition) {
        return row -> condition.test(items.get(row));
    }

    // ---- changing, any thread

    /** Replaces all items. Keeps sorting, filter and as much of the selection as still fits. Any thread. */
    public void setAll(Collection<? extends T> newItems) {
        List<T> copy = new ArrayList<>(newItems);
        applyChange(() -> {
            if (list instanceof ObservableList<T> shown) {
                shown.setAll(copy);
            } else {
                items.clear();
                items.addAll(copy);
                fireDataChanged();
            }
        });
    }

    /** Appends an item. Any thread. */
    public void add(T item) {
        applyChange(() -> {
            list.add(item);
            if (list == items) {
                fireRowsInserted(items.size() - 1, items.size() - 1);
            }
        });
    }

    /** Inserts an item; the selection and the visible rows stay where they are. Any thread. */
    public void add(int index, T item) {
        applyChange(() -> {
            list.add(index, item);
            if (list == items) {
                fireRowsInserted(index, index);
            }
        });
    }

    /** Appends items. Any thread. */
    public void addAll(Collection<? extends T> newItems) {
        List<T> copy = new ArrayList<>(newItems);
        applyChange(() -> {
            int first = items.size();
            list.addAll(copy);
            if (list == items && !copy.isEmpty()) {
                fireRowsInserted(first, items.size() - 1);
            }
        });
    }

    /** Inserts items at {@code index} with one {@code INSERT} event. Any thread. */
    public void addAll(int index, Collection<? extends T> newItems) {
        List<T> copy = new ArrayList<>(newItems);
        applyChange(() -> {
            list.addAll(index, copy);
            if (list == items && !copy.isEmpty()) {
                fireRowsInserted(index, index + copy.size() - 1);
            }
        });
    }

    /** Replaces an item; repaints its row if visible. Any thread. */
    public void set(int index, T item) {
        applyChange(() -> {
            list.set(index, item);
            if (list == items) {
                fireRowsUpdated(index, index);
            }
        });
    }

    /** Replaces the item at {@code index} with {@code change} applied to it, on the JavaFX thread. Any thread. */
    public void update(int index, UnaryOperator<T> change) {
        applyChange(() -> {
            list.set(index, change.apply(items.get(index)));
            if (list == items) {
                fireRowsUpdated(index, index);
            }
        });
    }

    /** Removes an item. Any thread. */
    public void remove(int index) {
        applyChange(() -> {
            list.remove(index);
            if (list == items) {
                fireRowsDeleted(index, index);
            }
        });
    }

    /// Removes the items at the given model rows, e.g. `people.removeRows(grid.getSelectedModelRows())`. The rows may
    /// come in any order and more than once; they are taken as they are before the first one is removed. Fires one
    /// `DELETE` event per run of adjacent rows. Any thread.
    ///
    /// @param modelRows the rows to remove
    /// @throws IndexOutOfBoundsException if a row is out of range; nothing is removed then
    public void removeRows(int... modelRows) {
        int[] rows = modelRows.clone();
        applyChange(() -> {
            BitSet removed = new BitSet();
            for (int row : rows) {
                removed.set(Objects.checkIndex(row, items.size()));
            }
            removeMarked(removed);
        });
    }

    /** Removes all items matching {@code condition}, one {@code DELETE} event per run of adjacent rows. Any thread. */
    public void removeIf(Predicate<? super T> condition) {
        applyChange(() -> {
            BitSet removed = new BitSet();
            for (int i = 0; i < items.size(); i++) {
                if (condition.test(items.get(i))) {
                    removed.set(i);
                }
            }
            removeMarked(removed);
        });
    }

    /** Removes all items. Any thread. */
    public void clear() {
        applyChange(() -> {
            int count = items.size();
            list.clear();
            if (list == items && count > 0) {
                fireRowsDeleted(0, count - 1);
            }
        });
    }

    /** Removes the items at the set bits and fires one {@code DELETE} per run of adjacent rows. */
    private void removeMarked(BitSet removed) {
        // Highest run first, and each run removed right before its event, so every event matches the model and
        // a sorted or filtered grid can follow it without sorting again.
        for (int last = removed.length() - 1; last >= 0; ) {
            int first = removed.previousClearBit(last) + 1;
            list.subList(first, last + 1).clear();
            if (list == items) {
                fireRowsDeleted(first, last);
            }
            last = removed.previousSetBit(first - 1);
        }
    }

    /// Copies a change of the list shown with [#of] into `items` one part at a time and fires each part's event
    /// right after it, so every event matches the rows the grid reads, also for a change in several parts such
    /// as `removeAll`. A part's indexes count from the list as the parts before it left it.
    private void listChanged(ListChangeListener.Change<? extends T> change) {
        while (change.next()) {
            int from = change.getFrom();
            int to = change.getTo();
            int removed = change.getRemovedSize();
            if (change.wasPermutated()) {
                List<T> moved = new ArrayList<>(items.subList(from, to));
                for (int i = from; i < to; i++) {
                    items.set(change.getPermutation(i), moved.get(i - from));
                }
                fireDataChanged();
            } else if (change.wasUpdated()) {
                fireRowsUpdated(from, to - 1);
            } else if (change.wasReplaced() && removed == 1 && to == from + 1) { // set
                items.set(from, change.getAddedSubList().getFirst());
                fireRowsUpdated(from, from);
            } else if (change.wasReplaced() && from == 0 && to == change.getList().size()) { // setAll
                items.subList(0, removed).clear();
                items.addAll(0, change.getAddedSubList());
                fireDataChanged();
            } else {
                if (change.wasRemoved()) {
                    items.subList(from, from + removed).clear();
                    fireRowsDeleted(from, from + removed - 1);
                }
                if (change.wasAdded()) {
                    items.addAll(from, change.getAddedSubList());
                    fireRowsInserted(from, to - 1);
                }
            }
        }
    }

    // ---- GridModel

    @Override
    public int getRowCount() {
        return items.size();
    }

    @Override
    public int getColumnCount() {
        return columns.count();
    }

    @Override
    public Object getValueAt(int row, int column) {
        return columns.value(items.get(row), column);
    }

    @Override
    public String getColumnName(int column) {
        return columns.name(column);
    }

    @Override
    public String getRowName(int row) {
        return columns.rowName(items.get(row));
    }

    /** The declared type for records, otherwise the class of the first non-null value among the first 10 items. */
    @Override
    public Class<?> getColumnClass(int column) {
        return columns.type(column, items::get, items.size());
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return columns.editable(column);
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        set(row, columns.update(items.get(row), column, value));
    }
}
