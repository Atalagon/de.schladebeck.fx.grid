package de.schladebeck.fx.grid.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.Fx.ArrayModel;
import de.schladebeck.fx.grid.model.AbstractGridModel;
import de.schladebeck.fx.grid.model.SortKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ViewRowsTest {

    private final ArrayModel model = new ArrayModel(new Object[][] {{3, 0}, {1, 1}, {3, 2}, {2, 3}, {1, 4}});
    private final ViewRows rows = new ViewRows();

    ViewRowsTest() {
        rows.setModel(model);
    }

    @Test
    void identityWithoutFilterAndSort() {
        assertTrue(rows.isIdentity());
        assertEquals(5, rows.count());
        assertEquals(3, rows.toModel(3));
        assertEquals(3, rows.toView(3));
    }

    @Test
    void sortsAscendingAndStable() {
        rows.sort(0, true);

        assertArrayEquals(new int[] {1, 4, 3, 0, 2}, viewOrder());
    }

    @Test
    void sortsDescendingAndStable() {
        rows.sort(0, false);

        assertArrayEquals(new int[] {0, 2, 3, 1, 4}, viewOrder());
    }

    @Test
    void nullsFirstMixedNumbersByValue() {
        rows.setModel(new ArrayModel(new Object[][] {{2.5}, {null}, {2}, {1L}}));
        rows.sort(0, true);

        assertArrayEquals(new int[] {1, 3, 2, 0}, viewOrder());
    }

    @Test
    void numbersKeepTheirOrderAsPrimitiveKeys() {
        rows.setModel(new ArrayModel(new Object[][] {
                {Double.NaN}, {0.0}, {null}, {-0.0}, {Double.NEGATIVE_INFINITY}, {1}, {1.0f}}));
        rows.sort(0, true);
        assertArrayEquals(new int[] {2, 4, 3, 1, 5, 6, 0}, viewOrder());

        rows.sort(0, false);
        assertArrayEquals(new int[] {0, 5, 6, 1, 3, 4, 2}, viewOrder(), "nulls last, ties in model order");

        long big = 1L << 60;
        rows.setModel(new ArrayModel(new Object[][] {{big + 1}, {big}, {-big}}));
        rows.sort(0, true);
        assertArrayEquals(new int[] {2, 1, 0}, viewOrder(), "longs a double cannot hold compare exactly");
    }

    @Test
    void textIgnoresCaseFirst() {
        rows.setModel(new ArrayModel(new Object[][] {{"B9"}, {"a0"}, {"A0"}, {"b"}}));
        rows.sort(0, true);

        assertArrayEquals(new int[] {2, 1, 3, 0}, viewOrder());
    }

    @Test
    void enumsByDeclaringClassAndOrdinal() {
        rows.setModel(new ArrayModel(new Object[][] {{Op.TIMES}, {Size.S}, {Op.MINUS}, {Op.PLUS}}));
        rows.sort(0, true);

        assertArrayEquals(new int[] {3, 2, 0, 1}, viewOrder(), "constants with bodies by ordinal too");
    }

    @Test
    void customComparatorSeesNoNullsAndStaysForRebuilds() {
        rows.setModel(new ArrayModel(new Object[][] {{"ccc"}, {null}, {"a"}, {"bb"}, {"d"}}));
        rows.sort(0, true, Comparator.comparingInt(value -> value.toString().length()));
        assertArrayEquals(new int[] {1, 2, 4, 3, 0}, viewOrder());

        rows.invalidate();
        assertArrayEquals(new int[] {1, 2, 4, 3, 0}, viewOrder());

        rows.sort(0, true);
        assertArrayEquals(new int[] {1, 2, 3, 0, 4}, viewOrder(), "the default order again");
    }

    @Test
    void filtersThenSortsAndMapsBack() {
        rows.setFilter(row -> (Integer) model.getValueAt(row, 0) != 2);
        rows.sort(0, false);

        assertArrayEquals(new int[] {0, 2, 1, 4}, viewOrder());
        assertEquals(-1, rows.toView(3), "filtered out");
        assertEquals(1, rows.toView(2));
    }

    @Test
    void rebuildsOnceForManyChanges() {
        int[] calls = {0};
        rows.setFilter(row -> {
            calls[0]++;
            return true;
        });
        for (int i = 0; i < 100; i++) {
            rows.invalidate();
        }
        assertEquals(0, calls[0], "nothing is computed before it is needed");

        rows.count();
        rows.toModel(2);
        assertEquals(5, calls[0], "one pass over the rows");
    }

    @Test
    void picksUpInsertedRowsAfterInvalidate() {
        rows.sort(0, true);
        model.rows = new Object[][] {{3, 0}, {1, 1}, {0, 2}};
        rows.invalidate();

        assertArrayEquals(new int[] {2, 1, 0}, viewOrder());
    }

    @Test
    void failedRebuildIsRetried() {
        rows.count();
        boolean[] fail = {true, true};
        rows.setFilter(row -> {
            if (fail[0]) {
                fail[0] = false;
                throw new IllegalStateException();
            }
            return row != 3;
        });
        assertThrows(IllegalStateException.class, rows::count);
        assertEquals(4, rows.count(), "filtered, not the old mapping");

        rows.sort(0, true, (a, b) -> {
            if (fail[1]) {
                fail[1] = false;
                throw new IllegalStateException();
            }
            return Integer.compare((Integer) a, (Integer) b);
        });
        assertThrows(IllegalStateException.class, rows::count);
        assertArrayEquals(new int[] {1, 4, 0, 2}, viewOrder(), "sorted, not the old mapping");
    }

    @Test
    void insertsIntoASortedViewWithoutRebuilding() {
        FakeModel data = new FakeModel(1_000, -2);
        int[] tests = {0};
        rows.setModel(data);
        rows.setFilter(row -> {
            tests[0]++;
            return true;
        });
        rows.sort(0, false);
        rows.count();
        data.reads = 0;
        tests[0] = 0;

        data.values.add(500, 123);
        rows.rowsInserted(500, 1);

        assertEquals(1, tests[0], "only the new row is filtered");
        assertTrue(data.reads <= 2 * 11, data.reads + " values read for a binary search");
        assertEquals(1_001, rows.count());
        assertEquals(500, rows.toModel(1_000 - 1 - 123 + 1), "after the 123 in the old rows, before the 122");
    }

    @Test
    void failedInsertLeavesARebuild() {
        boolean[] fail = {false};
        rows.setFilter(row -> {
            if (fail[0]) {
                fail[0] = false;
                throw new IllegalStateException();
            }
            return true;
        });
        rows.sort(0, true);
        rows.count();
        model.rows = new Object[][] {{3, 0}, {1, 1}, {3, 2}, {2, 3}, {1, 4}, {0, 5}};
        fail[0] = true;

        assertThrows(IllegalStateException.class, () -> rows.rowsInserted(5, 1));
        assertArrayEquals(new int[] {5, 1, 4, 3, 0, 2}, viewOrder());
    }

    @Test
    void incrementalInsertsAndDeletesEqualARebuild() {
        Random random = new Random(7);
        List<Comparator<Object>> comparators = new ArrayList<>();
        comparators.add(null);
        comparators.add(Comparator.comparingInt(value -> value.toString().length()));
        for (int round = 0; round < 400; round++) {
            ArrayModel data = new ArrayModel(randomRows(random, 1 + random.nextInt(40)));
            List<IntPredicate> filters = new ArrayList<>();
            filters.add(null);
            filters.add(row -> (Integer) data.getValueAt(row, 0) % 3 != 0);
            filters.add(row -> data.getValueAt(row, 2) != null);
            IntPredicate filter = filters.get(random.nextInt(filters.size()));
            int column = random.nextInt(-1, 5);
            boolean ascending = random.nextBoolean();
            Comparator<Object> comparator = comparators.get(random.nextInt(comparators.size()));
            List<SortKey> keys = new ArrayList<>();
            if (column >= 0) {
                keys.add(new SortKey(column, ascending));
                int second = (column + 1 + random.nextInt(4)) % 5;
                if (random.nextBoolean()) {
                    keys.add(new SortKey(second, random.nextBoolean()));
                }
            }
            ViewRows view = configure(new ViewRows(), data, filter, keys, comparator);

            for (int step = 0; step < 20; step++) {
                boolean built = random.nextInt(5) > 0;
                int version = built ? view.version() : 0;
                int n = data.getRowCount();
                List<Object[]> list = new ArrayList<>(List.of(data.rows));
                String change;
                if (n == 1 || random.nextBoolean()) {
                    int first = random.nextInt(n + 1);
                    int count = 1 + random.nextInt(4);
                    list.addAll(first, List.of(randomRows(random, count)));
                    data.rows = list.toArray(Object[][]::new);
                    view.rowsInserted(first, count);
                    change = "insert " + count + " at " + first;
                } else {
                    int first = random.nextInt(n - 1);
                    int count = 1 + random.nextInt(Math.min(4, n - 1 - first));
                    list.subList(first, first + count).clear();
                    data.rows = list.toArray(Object[][]::new);
                    view.rowsDeleted(first, count);
                    change = "delete " + count + " at " + first;
                }

                String where = "round " + round + ", step " + step + ": " + change + ", keys " + keys;
                if (built) {
                    assertNotEquals(version, view.version(), where);
                }
                ViewRows fresh = configure(new ViewRows(), data, filter, keys, comparator);
                assertArrayEquals(viewOrder(fresh), viewOrder(view), where);
                for (int row = 0; row < data.getRowCount(); row++) {
                    assertEquals(fresh.toView(row), view.toView(row), where);
                }
            }
        }
    }

    @Test
    void lookupsRightAfterAChangeFindTheRowsWithoutTheInverse() {
        Random random = new Random(11);
        for (int column = -1; column < 5; column++) {
            ArrayModel data = new ArrayModel(randomRows(random, 20_000));
            IntPredicate filter = column % 2 == 0 ? null : row -> (Integer) data.getValueAt(row, 0) % 3 != 0;
            boolean ascending = random.nextBoolean();
            ViewRows view = configure(new ViewRows(), data, filter, column, ascending, null);
            view.count();
            List<Object[]> list = new ArrayList<>(List.of(data.rows));
            list.addAll(7_000, List.of(randomRows(random, 3)));
            data.rows = list.toArray(Object[][]::new);
            view.rowsInserted(7_000, 3);
            if (column == 0) {
                data.rows[42][0] = ascending ? 100 : -100; // placed by its old value, now sorts elsewhere
            }
            int[] order = viewOrder(view);
            int[] expected = new int[data.getRowCount()];
            Arrays.fill(expected, -1);
            for (int i = 0; i < order.length; i++) {
                expected[order[i]] = i;
            }

            // Up to n / 1024 lookups (13 here) search the view; the misplaced row and any later lookups use the
            // inverse.
            List<Integer> lookups = new ArrayList<>(List.of(7_000, 7_001, 7_002));
            random.ints(8, 0, data.getRowCount()).forEach(lookups::add);
            lookups.addAll(List.of(42, 19_999, 0));
            for (int row : lookups) {
                assertEquals(expected[row], view.toView(row), "column " + column + ", row " + row);
            }
        }
    }

    @Test
    void textSortsCaseInsensitivelyWhetherItRepeatsOrNot() {
        String[] words = {"apfel", "Apfel", "ÄPFEL", "äpfel", "straße", "STRASSE", "ı", "I", "i", "", "a b",
                "Zebra", "zebra", null};
        Random random = new Random(3);
        Object[][] repeated = new Object[40_000][];
        Object[][] unique = new Object[40_000][];
        for (int row = 0; row < repeated.length; row++) {
            String word = words[random.nextInt(words.length)];
            repeated[row] = new Object[] {word};
            unique[row] = new Object[] {word == null ? null : word + row / 2}; // pairs that differ in case only
        }
        Comparator<Object> text = Comparator.nullsFirst(
                Comparator.comparing(Object::toString, String.CASE_INSENSITIVE_ORDER).thenComparing(Object::toString));
        for (Object[][] rows : List.of(repeated, unique)) {
            for (boolean ascending : new boolean[] {true, false}) {
                ArrayModel data = new ArrayModel(rows);
                ViewRows view = configure(new ViewRows(), data, null, 0, ascending, null);
                assertArrayEquals(expectedOrder(data, ascending ? text : text.reversed()), viewOrder(view));
            }
        }
    }

    @Test
    void repeatedValuesSortByTheirComparatorToo() {
        Random random = new Random(5);
        Object[][] rows = new Object[40_000][];
        for (int row = 0; row < rows.length; row++) {
            LocalDate day = LocalDate.of(2026, 1, 1).plusDays(random.nextInt(365));
            rows[row] = new Object[] {random.nextInt(50) == 0 ? null : day};
        }
        ArrayModel data = new ArrayModel(rows);
        Comparator<Object> newestFirst = (a, b) -> ((LocalDate) b).compareTo((LocalDate) a);
        ViewRows view = configure(new ViewRows(), data, null, 0, true, newestFirst);

        assertArrayEquals(expectedOrder(data, Comparator.nullsFirst(newestFirst)), viewOrder(view));
    }

    /** The model rows sorted stably by the values of column 0. */
    private static int[] expectedOrder(ArrayModel data, Comparator<Object> order) {
        return IntStream.range(0, data.getRowCount()).boxed()
                .sorted(Comparator.comparing(row -> data.getValueAt(row, 0), order))
                .mapToInt(Integer::intValue).toArray();
    }

    @Test
    void severalKeysSortByTheFirstThenByTheNext() {
        Random random = new Random(9);
        ArrayModel data = new ArrayModel(randomRows(random, 20_000));
        ViewRows view = configure(new ViewRows(), data, null,
                List.of(new SortKey(0, true), new SortKey(2, false), new SortKey(1, true)), null);

        Comparator<Object> text = Comparator.nullsFirst(
                Comparator.comparing(Object::toString, String.CASE_INSENSITIVE_ORDER).thenComparing(Object::toString));
        Comparator<Object> number = Comparator.nullsFirst(Comparator.comparingDouble(v -> ((Number) v).doubleValue()));
        Comparator<Integer> order = Comparator.<Integer, Object>comparing(row -> data.getValueAt(row, 0), number)
                .thenComparing(row -> data.getValueAt(row, 2), text.reversed())
                .thenComparing(row -> data.getValueAt(row, 1), number);
        int[] expected = IntStream.range(0, data.getRowCount()).boxed().sorted(order)
                .mapToInt(Integer::intValue).toArray();

        assertArrayEquals(expected, viewOrder(view));
        assertEquals(List.of(new SortKey(0, true), new SortKey(2, false), new SortKey(1, true)), view.sortKeys());
    }

    @Test
    void askingTheModelToSortDoesNotSortAllRowsMeanwhile() {
        FakeModel data = new FakeModel(100, 0);
        rows.setModel(data);
        data.addGridModelListener(event -> rows.toModel(rows.count() - 1));

        rows.sort(0, true);

        assertEquals(List.of("0 asc"), data.sorts);
        assertEquals(0, data.reads, "the listener saw the rows unsorted, not a local sort");
        assertTrue(rows.isIdentity());
    }

    @Test
    void modelThatDeclinesAColumnReturnsToNaturalOrderFirst() {
        FakeModel data = new FakeModel(100, 0);
        rows.setModel(data);
        data.addGridModelListener(event -> rows.toModel(rows.count() - 1));
        rows.sort(0, false);

        rows.sort(1, true);

        assertEquals(List.of("0 desc", "1 asc", "-1 asc"), data.sorts);
        assertEquals(0, data.reads);
        assertEquals(99, rows.toModel(0), "the grid sorts column 1 itself");
    }

    @Test
    void clearSortAndSetModelResetAModelThatSortedItself() {
        FakeModel data = new FakeModel(10, 0);
        rows.setModel(data);
        rows.sort(0, true);
        rows.clearSort();
        assertEquals(List.of("0 asc", "-1 asc"), data.sorts);

        rows.sort(0, false);
        rows.setModel(model);
        assertEquals(List.of("0 asc", "-1 asc", "0 desc", "-1 asc"), data.sorts);
        assertEquals(List.of(), rows.sortKeys());
    }

    private static ViewRows configure(ViewRows view, ArrayModel data, IntPredicate filter, int column,
                                      boolean ascending, Comparator<Object> comparator) {
        return configure(view, data, filter, column < 0 ? List.of() : List.of(new SortKey(column, ascending)),
                comparator);
    }

    private static ViewRows configure(ViewRows view, ArrayModel data, IntPredicate filter, List<SortKey> keys,
                                      Comparator<Object> comparator) {
        view.setModel(data);
        view.setFilter(filter);
        view.sort(keys, column -> comparator);
        return view;
    }

    /** Values whose default order is consistent: ties, nulls, case, mixed numbers, longs beyond double. */
    private static Object[][] randomRows(Random random, int count) {
        long big = 1L << 60;
        Object[][] result = new Object[count][];
        for (int i = 0; i < count; i++) {
            result[i] = new Object[] {
                    random.nextInt(10),
                    pick(random, null, -0.0, 0.0, 1.5, -2.0, Double.NaN, Double.NEGATIVE_INFINITY),
                    pick(random, null, "a", "A", "b", "B9", "a0", "A0", ""),
                    pick(random, null, 1, 2L, 1.5, 2.0f, new BigDecimal("0.5"), (short) 3, (byte) -1),
                    pick(random, null, big, big + 1, -big, 7L)};
        }
        return result;
    }

    private static Object pick(Random random, Object... values) {
        return values[random.nextInt(values.length)];
    }

    private int[] viewOrder() {
        return viewOrder(rows);
    }

    private static int[] viewOrder(ViewRows view) {
        return IntStream.range(0, view.count()).map(view::toModel).toArray();
    }

    private enum Op {
        PLUS {
        },
        MINUS {
        },
        TIMES
    }

    private enum Size {
        S
    }

    /// Two columns: the values and their negation. Sorts itself by one column (-2: none), firing `DATA` from
    /// `sortRows` like a paged model, and counts value reads.
    private static final class FakeModel extends AbstractGridModel {
        final List<Integer> values = new ArrayList<>();
        final List<String> sorts = new ArrayList<>();
        final int sortsItself;
        int reads;

        FakeModel(int rows, int sortsItself) {
            for (int row = 0; row < rows; row++) {
                values.add(row);
            }
            this.sortsItself = sortsItself;
        }

        @Override
        public int getRowCount() {
            return values.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public Object getValueAt(int row, int column) {
            reads++;
            return column == 0 ? values.get(row) : -values.get(row);
        }

        @Override
        public boolean sortRows(List<SortKey> keys, IntFunction<Comparator<Object>> order) {
            int column = keys.isEmpty() ? -1 : keys.getFirst().column();
            sorts.add(column + (keys.isEmpty() || keys.getFirst().ascending() ? " asc" : " desc"));
            if (column != -1 && column != sortsItself) {
                return false;
            }
            fireDataChanged();
            return true;
        }
    }
}
