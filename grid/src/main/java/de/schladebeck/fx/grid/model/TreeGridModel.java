package de.schladebeck.fx.grid.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/// A tree of items shown as rows: every item is a row, and the children of an expanded item follow it. Show it in a
/// [de.schladebeck.fx.grid.TreeGrid], which indents the first column and adds the triangles that expand and
/// collapse.
///
/// ```java
/// TreeGridModel<Unit> company = new TreeGridModel<Unit>(Unit::children)
///         .column("Name", Unit::name)
///         .column("People", Unit::people);
/// company.setRoots(List.of(headquarters));
/// Grid grid = new TreeGrid(company);
/// ```
///
/// - Expanding inserts the rows of the children and collapsing deletes them, so a grid keeps its selection and its
///   scroll position as with any other change.
/// - The children function is called when an item is first expanded or shown with its triangle, so a large tree
///   can be loaded as it is opened.
/// - Each item in the tree is expanded on its own. [#setRoots] keeps items expanded that equal (`equals`) items
///   expanded before.
/// - Sorting sorts the children of each item, so the tree stays a tree.
/// - [#setRoots] and the builders may be called from any thread, see [AbstractGridModel#applyChange]. Reading
///   methods and [#setExpanded] belong to the JavaFX application thread, where row numbers are current.
///
/// @param <T> the type of the items
public class TreeGridModel<T> extends AbstractGridModel {

    /** An item in the tree, with its children once they were asked for. */
    private static final class Node<T> {

        final T item;
        final int depth;
        /** Position among its siblings as the children function returned them, for the unsorted order. */
        final int index;
        List<Node<T>> children;
        boolean expanded;

        Node(T item, int depth, int index) {
            this.item = item;
            this.depth = depth;
            this.index = index;
        }
    }

    private final Function<? super T, ? extends Collection<? extends T>> children;
    private final ItemColumns<T> columns = new ItemColumns<>();
    private List<Node<T>> roots = new ArrayList<>();
    /** The shown nodes in row order: roots, and the children of expanded nodes after them. */
    private final List<Node<T>> rows = new ArrayList<>();
    /** While {@link #setRoots} runs: the items that were expanded, so equal new ones are expanded too. */
    private Set<T> expandedBefore;
    private List<SortKey> sortKeys = List.of();
    private Predicate<? super T> hasChildren;
    /** The order of each sort column's values, from the grid, see {@link #sortRows}. */
    private IntFunction<Comparator<Object>> valueOrder;

    /// An empty tree; add columns with [#column] and items with [#setRoots].
    ///
    /// @param children the children of an item, empty or `null` for none
    public TreeGridModel(Function<? super T, ? extends Collection<? extends T>> children) {
        this.children = children;
    }

    /** Adds a read-only column. Returns this model for chaining. Any thread. */
    public <V> TreeGridModel<T> column(String name, Function<? super T, ? extends V> getter) {
        applySetup(() -> {
            columns.add(name, null, getter, null);
            fireStructureChanged();
        });
        return this;
    }

    /// Tells whether an item has children without calling the children function, for trees that load their
    /// children slowly: an item's triangle then depends on this test until the item is expanded. Any thread.
    ///
    /// @param test whether an item has children
    /// @return this model, for chaining
    public TreeGridModel<T> hasChildren(Predicate<? super T> test) {
        applySetup(() -> {
            hasChildren = test;
            fireDataChanged();
        });
        return this;
    }

    /** Replaces all items with these top-level ones. Items expanded before stay expanded. Any thread. */
    public void setRoots(Collection<? extends T> items) {
        List<T> copy = new ArrayList<>(items);
        applyChange(() -> {
            expandedBefore = new HashSet<>();
            addExpanded(roots, expandedBefore);
            roots = nodes(copy, 0);
            rows.clear();
            addShown(roots, rows);
            expandedBefore = null;
            fireDataChanged();
        });
    }

    public T getItem(int row) {
        return rows.get(row).item;
    }

    /** 0 for the top-level items, 1 for their children, and so on. */
    public int getDepth(int row) {
        return rows.get(row).depth;
    }

    /** Whether the item has no children: by the {@link #hasChildren} test, or by asking the children function. */
    public boolean isLeaf(int row) {
        Node<T> node = rows.get(row);
        return node.children == null && hasChildren != null ? !hasChildren.test(node.item)
                : children(node).isEmpty();
    }

    public boolean isExpanded(int row) {
        return rows.get(row).expanded && !isLeaf(row);
    }

    /** The row of the item's parent, or -1 for a top-level item. */
    public int getParentRow(int row) {
        int depth = getDepth(row);
        for (int r = row - 1; r >= 0; r--) {
            if (rows.get(r).depth < depth) {
                return r;
            }
        }
        return -1;
    }

    /// Shows or hides the children of the item in a row. Expanding inserts the rows of its children, and of
    /// theirs where those are expanded; collapsing deletes them and keeps which of them are expanded.
    ///
    /// @param row    the row of the item
    /// @param expand whether to show its children
    public void setExpanded(int row, boolean expand) {
        Node<T> node = rows.get(row);
        if (expand == isExpanded(row) || isLeaf(row)) {
            return;
        }
        applyChange(() -> {
            node.expanded = expand;
            if (expand) {
                List<Node<T>> shown = new ArrayList<>();
                addShown(children(node), shown);
                rows.addAll(row + 1, shown);
                fireRowsInserted(row + 1, row + shown.size());
            } else {
                int end = row + 1;
                while (end < rows.size() && rows.get(end).depth > node.depth) {
                    end++;
                }
                rows.subList(row + 1, end).clear();
                if (end > row + 1) {
                    fireRowsDeleted(row + 1, end - 1);
                }
            }
        });
    }

    /// Sorts the children of each item, and the top-level items, by the keys in the order the grid passes; no keys
    /// bring back the order of the children function.
    @Override
    public boolean sortRows(List<SortKey> keys, IntFunction<Comparator<Object>> order) {
        sortKeys = List.copyOf(keys);
        valueOrder = order;
        sortLoaded(roots);
        rows.clear();
        addShown(roots, rows);
        fireDataChanged();
        return true;
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return columns.count();
    }

    @Override
    public Object getValueAt(int row, int column) {
        return columns.value(rows.get(row).item, column);
    }

    @Override
    public String getColumnName(int column) {
        return columns.name(column);
    }

    /** The class of the first non-null value among the first 10 shown rows. */
    @Override
    public Class<?> getColumnClass(int column) {
        return columns.type(column, this::getItem, rows.size());
    }

    private List<Node<T>> nodes(Collection<? extends T> items, int depth) {
        List<Node<T>> nodes = new ArrayList<>(items.size());
        for (T item : items) {
            Node<T> node = new Node<>(item, depth, nodes.size());
            node.expanded = expandedBefore != null && expandedBefore.contains(item);
            nodes.add(node);
        }
        sort(nodes);
        return nodes;
    }

    private List<Node<T>> children(Node<T> node) {
        if (node.children == null) {
            Collection<? extends T> items = children.apply(node.item);
            node.children = nodes(items == null ? List.of() : items, node.depth + 1);
        }
        return node.children;
    }

    /** Adds the nodes and the shown descendants of the expanded ones, in row order. */
    private void addShown(List<Node<T>> nodes, List<Node<T>> into) {
        for (Node<T> node : nodes) {
            into.add(node);
            if (node.expanded) {
                addShown(children(node), into);
            }
        }
    }

    /** Collects the items of the expanded nodes among the loaded ones. */
    private void addExpanded(List<Node<T>> nodes, Set<T> into) {
        for (Node<T> node : nodes) {
            if (node.expanded) {
                into.add(node.item);
            }
            if (node.children != null) {
                addExpanded(node.children, into);
            }
        }
    }

    private void sortLoaded(List<Node<T>> nodes) {
        sort(nodes);
        for (Node<T> node : nodes) {
            if (node.children != null) {
                sortLoaded(node.children);
            }
        }
    }

    /// Sorts siblings by the sort keys, ties in the order of the children function. Each value is read once, and
    /// the merge sort accepts orders that contradict themselves, as orders of mixed types can.
    private void sort(List<Node<T>> nodes) {
        int n = nodes.size();
        Object[][] values = new Object[sortKeys.size()][n];
        for (int k = 0; k < values.length; k++) {
            for (int i = 0; i < n; i++) {
                values[k][i] = columns.value(nodes.get(i).item, sortKeys.get(k).column());
            }
        }
        List<Comparator<Object>> orders = sortKeys.stream().map(key -> key.ascending()
                ? valueOrder.apply(key.column()) : valueOrder.apply(key.column()).reversed()).toList();
        Integer[] order = new Integer[n];
        Arrays.setAll(order, i -> i);
        mergeSort(order, (a, b) -> {
            for (int k = 0; k < values.length; k++) {
                int c = orders.get(k).compare(values[k][a], values[k][b]);
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(nodes.get(a).index, nodes.get(b).index);
        });
        List<Node<T>> sorted = new ArrayList<>(n);
        for (int i : order) {
            sorted.add(nodes.get(i));
        }
        nodes.clear();
        nodes.addAll(sorted);
    }

    /** Bottom-up merge sort, stable; takes from the right run only if strictly smaller. */
    private static <E> void mergeSort(E[] a, Comparator<? super E> order) {
        E[] src = a;
        E[] dst = a.clone();
        for (int width = 1; width < a.length; width *= 2) {
            for (int lo = 0; lo < a.length; lo += 2 * width) {
                int mid = Math.min(lo + width, a.length);
                int hi = Math.min(lo + 2 * width, a.length);
                int i = lo;
                int j = mid;
                int k = lo;
                while (i < mid && j < hi) {
                    dst[k++] = order.compare(src[j], src[i]) < 0 ? src[j++] : src[i++];
                }
                while (i < mid) {
                    dst[k++] = src[i++];
                }
                while (j < hi) {
                    dst[k++] = src[j++];
                }
            }
            E[] swap = src;
            src = dst;
            dst = swap;
        }
        if (src != a) {
            System.arraycopy(src, 0, a, 0, a.length);
        }
    }
}
