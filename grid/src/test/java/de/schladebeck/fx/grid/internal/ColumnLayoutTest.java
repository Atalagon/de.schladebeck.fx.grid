package de.schladebeck.fx.grid.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ColumnLayoutTest {

    private final ColumnLayout layout = new ColumnLayout();

    @Test
    void findsColumnsByPosition() {
        layout.resize(10_000, 100);

        assertEquals(1_000_000, layout.total());
        assertEquals(-1, layout.columnAt(-0.1));
        assertEquals(0, layout.columnAt(0));
        assertEquals(0, layout.columnAt(99.9));
        assertEquals(1, layout.columnAt(100));
        assertEquals(9_999, layout.columnAt(999_999));
        assertEquals(-1, layout.columnAt(1_000_000));
    }

    @Test
    void widthChangeShiftsFollowingColumns() {
        layout.resize(3, 100);
        layout.setModelWidth(1, 50);

        assertEquals(100, layout.start(1));
        assertEquals(150, layout.start(2));
        assertEquals(250, layout.total());
        assertEquals(2, layout.columnAt(150));
    }

    @Test
    void resizeKeepsOwnWidthsAndOrder() {
        layout.resize(2, 100);
        layout.setModelWidth(0, 40);
        layout.move(0, 1);
        layout.resize(3, 70);

        assertArrayEquals(new int[] {1, 0, 2}, layout.order());
        assertEquals(40, layout.modelWidth(0));
        assertEquals(70, layout.modelWidth(1), "columns without an own width follow the default");
        assertEquals(180, layout.total());
    }

    @Test
    void hundredMillionColumnsCostNothingUntilChanged() {
        layout.resize(100_000_000, 100, 20);
        layout.setModelWidth(50_000_000, 300);
        layout.setVisible(10, false);

        assertEquals(99_999_999, layout.count());
        assertEquals(1e10 - 100 + 200, layout.total(), 1e-3);
        assertEquals(11, layout.toModel(10), "hidden column 10 is skipped");
        assertEquals(49_999_999, layout.toView(50_000_000));
        assertEquals(49_999_999, layout.columnAt(layout.start(49_999_999) + 250));
        assertEquals(300, layout.width(49_999_999));
    }

    @Test
    void hiddenColumnsMapBothWays() {
        layout.resize(10, 100);
        for (int hidden : new int[] {0, 3, 4, 9}) {
            layout.setVisible(hidden, false);
        }
        int[] expected = {1, 2, 5, 6, 7, 8};
        for (int view = 0; view < expected.length; view++) {
            assertEquals(expected[view], layout.toModel(view));
            assertEquals(view, layout.toView(expected[view]));
        }
        assertEquals(-1, layout.toView(3));
    }

    @Test
    void moveLeftAndRight() {
        layout.resize(4, 100);
        layout.move(0, 2);
        assertArrayEquals(new int[] {1, 2, 0, 3}, layout.order());

        layout.move(3, 0);
        assertArrayEquals(new int[] {3, 1, 2, 0}, layout.order());
        assertEquals(3, layout.toModel(0));
        assertEquals(0, layout.toView(3));
    }

    @Test
    void movedColumnKeepsItsWidth() {
        layout.resize(3, 100);
        layout.setModelWidth(0, 30);
        layout.move(0, 2);

        assertEquals(30, layout.width(2));
        assertEquals(200, layout.start(2));
    }

    @Test
    void hiddenColumnsAreFilteredOutLikeRows() {
        layout.resize(4, 100);
        layout.setVisible(1, false);

        assertEquals(3, layout.count());
        assertEquals(4, layout.modelCount());
        assertEquals(2, layout.toModel(1));
        assertEquals(-1, layout.toView(1));
        assertEquals(300, layout.total());
        assertArrayEquals(new int[] {0, 1, 2, 3}, layout.order(), "order keeps hidden columns");

        layout.move(2, 0);
        layout.setVisible(1, true);
        assertArrayEquals(new int[] {3, 0, 1, 2}, layout.order(), "a shown column comes back where it was");
    }

    @Test
    void rejectsOrderThatIsNoPermutation() {
        layout.resize(3, 100);

        assertThrows(IllegalArgumentException.class, () -> layout.setOrder(new int[] {0, 0, 1}));
        assertThrows(IllegalArgumentException.class, () -> layout.setOrder(new int[] {0, 1}));
        assertThrows(IllegalArgumentException.class, () -> layout.setOrder(new int[] {0, 1, 3}));
        assertThrows(IllegalArgumentException.class, () -> layout.setOrder(new int[] {-1, 0, 1}));
        assertTrue(layout.isNaturalOrder(), "a rejected order changes nothing");
    }

    @Test
    void identityOrderIsNaturalOrder() {
        layout.resize(4, 100);
        layout.move(0, 3);
        layout.setOrder(new int[] {0, 1, 2, 3});

        assertTrue(layout.isNaturalOrder());
        assertEquals(List.of(), layout.runs());
    }

    @Test
    void movingBackMergesRunsIntoNaturalOrder() {
        layout.resize(10, 100);
        layout.move(2, 7);
        assertArrayEquals(new int[][] {{0, 1}, {3, 7}, {2, 2}, {8, 9}}, layout.runs().toArray(int[][]::new));

        layout.move(7, 2);
        assertTrue(layout.isNaturalOrder());
    }

    @Test
    void runsRoundTrip() {
        layout.resize(10, 100);
        layout.move(2, 7);
        layout.move(9, 0);
        List<int[]> runs = layout.runs();
        int[] order = layout.order();

        layout.setNaturalOrder();
        layout.setRuns(runs);

        assertArrayEquals(order, layout.order());
        layout.setRuns(List.of());
        assertTrue(layout.isNaturalOrder());
        layout.setRuns(List.of(new int[] {0, 4}, new int[] {5, 9}));
        assertTrue(layout.isNaturalOrder(), "consecutive runs are merged");
    }

    @Test
    void rejectsRunsThatDoNotCoverEachColumnOnce() {
        layout.resize(10, 100);
        layout.move(0, 9);
        int[] order = layout.order();

        for (List<int[]> runs : List.of(
                List.of(new int[] {0, 5}, new int[] {5, 9}),
                List.of(new int[] {0, 3}, new int[] {5, 9}),
                List.of(new int[] {0, 8}),
                List.of(new int[] {0, 10}),
                List.of(new int[] {-1, 9}),
                List.of(new int[] {9, 0}),
                List.of(new int[] {0, 9}, new int[] {0, 9}),
                List.of(new int[] {0}))) {
            assertThrows(IllegalArgumentException.class, () -> layout.setRuns(runs));
        }
        assertArrayEquals(order, layout.order(), "a rejected order changes nothing");
    }

    @Test
    void clearForgetsOrderVisibilityWidthsAndLimits() {
        layout.resize(5, 100, 20);
        layout.move(0, 4);
        layout.setVisible(2, false);
        layout.setModelWidth(1, 30);
        layout.setLimits(1, 25, 40);

        layout.clear();

        assertTrue(layout.isNaturalOrder());
        assertEquals(5, layout.count());
        assertArrayEquals(new int[0], layout.hidden());
        assertEquals(100, layout.modelWidth(1));
        assertEquals(20, layout.minWidth(1));
        assertEquals(Double.MAX_VALUE, layout.maxWidth(1));
        assertEquals(500, layout.total());
    }

    @Test
    void resizeToNoColumnsAndBackIsNaturalOrder() {
        layout.resize(5, 100);
        layout.move(0, 4);
        layout.resize(0, 100);
        layout.resize(5, 100);

        assertTrue(layout.isNaturalOrder());
    }

    @Test
    void removedColumnsHaveNoViewColumn() {
        layout.resize(10, 100);
        layout.move(9, 0);
        layout.resize(5, 100);

        assertEquals(-1, layout.toView(9));
        assertEquals(-1, layout.toView(5));
        assertEquals(-1, layout.toView(-1));
        assertTrue(layout.isNaturalOrder(), "column 9 is gone, the rest is in order");
    }

    @Test
    void setsManyWidthsWithOneCall() {
        layout.resize(100_000, 100);
        Map<Integer, Double> widths = new HashMap<>();
        for (int column = 0; column < 100_000; column += 10) {
            widths.put(column, 50.0);
        }
        widths.put(5, 100.0);

        layout.setModelWidths(widths);

        assertEquals(100_000 * 100 - 10_000 * 50, layout.total());
        assertEquals(50 + 9 * 100, layout.start(10));
        assertEquals(10, layout.columnAt(950));
        layout.resize(100_000, 70);
        assertEquals(70, layout.modelWidth(5), "the default width is no own width");
    }

    @Test
    void hundredMillionColumnsMoveHideAndShowWithoutColumnArrays() throws ReflectiveOperationException {
        layout.resize(100_000_000, 100);
        layout.setModelWidth(99_999_999, 50);
        long allocated = allocatedBytes();
        long time = System.nanoTime();

        layout.move(10, 90_000_000);
        layout.setVisible(5, false);
        // Visible: 0-4, 6-9, 11-90,000,000, then 10, then the rest.
        assertEquals(99_999_999, layout.count());
        assertEquals(6, layout.toModel(5));
        assertEquals(10, layout.toModel(89_999_999));
        assertEquals(89_999_999, layout.toView(10));
        assertEquals(90_000_000, layout.toView(90_000_001));
        assertEquals(-1, layout.toView(5));
        assertEquals(89_999_999 * 100.0, layout.start(89_999_999));
        assertEquals(89_999_999, layout.columnAt(89_999_999 * 100.0 + 50));
        assertEquals(99_999_999 * 100.0 - 50, layout.total());
        layout.setVisible(5, true);
        layout.move(90_000_000, 10);

        long millis = (System.nanoTime() - time) / 1_000_000;
        long bytes = allocatedBytes() - allocated;
        assertTrue(layout.isNaturalOrder());
        assertTrue(millis < 100, "took " + millis + " ms");
        assertTrue(bytes < 1_000_000, "allocated " + bytes + " bytes");
    }

    @Test
    void matchesFullArrayReferenceOverRandomChanges() {
        for (int seed = 0; seed < 20; seed++) {
            Random random = new Random(seed);
            ColumnLayout tested = new ColumnLayout();
            Reference reference = new Reference();
            for (int step = 0; step < 300; step++) {
                String change = change(random, tested, reference);
                check(tested, reference, "seed " + seed + ", step " + step + ", " + change);
            }
        }
    }

    /** Applies the same random change to both layouts and describes it. */
    private static String change(Random random, ColumnLayout tested, Reference reference) {
        int modelCount = reference.order.length;
        int count = reference.visible().length;
        switch (random.nextInt(11)) {
            case 0, 1, 2, 3 -> {
                if (count == 0) {
                    return "no move";
                }
                int from = random.nextInt(count);
                int to = random.nextInt(count);
                tested.move(from, to);
                reference.move(from, to);
                return "move " + from + " to " + to;
            }
            case 4 -> {
                int column = random.nextInt(modelCount + 1);
                boolean visible = random.nextBoolean();
                if (column < modelCount) {
                    tested.setVisible(column, visible);
                    reference.setVisible(column, visible);
                }
                return "visible " + column + " " + visible;
            }
            case 5 -> {
                Set<Integer> hidden = new TreeSet<>();
                for (int i = random.nextInt(4); i > 0; i--) {
                    hidden.add(random.nextInt(modelCount + 3) - 1);
                }
                tested.setHidden(hidden);
                reference.hidden.clear();
                hidden.stream().filter(column -> column >= 0 && column < modelCount).forEach(reference.hidden::add);
                return "hidden " + hidden;
            }
            case 6 -> {
                int newCount = random.nextInt(25);
                tested.resize(newCount, 100);
                reference.resize(newCount);
                return "resize " + newCount;
            }
            case 7 -> {
                List<Integer> order = new ArrayList<>(IntStream.range(0, modelCount).boxed().toList());
                for (int i = random.nextInt(3); i > 0 && modelCount > 0; i--) {
                    Collections.swap(order, random.nextInt(modelCount), random.nextInt(modelCount));
                }
                reference.order = order.stream().mapToInt(Integer::intValue).toArray();
                tested.setOrder(reference.order);
                return "order " + order;
            }
            case 8 -> {
                tested.setNaturalOrder();
                reference.order = IntStream.range(0, modelCount).toArray();
                return "natural order";
            }
            case 9 -> {
                if (random.nextBoolean()) {
                    tested.setRuns(tested.runs());
                    return "runs round trip";
                }
                int column = random.nextInt(modelCount + 1);
                double width = random.nextBoolean() ? 100 : 10 + random.nextInt(200);
                tested.setModelWidth(column, width);
                if (width == 100) {
                    reference.widths.remove(column);
                } else {
                    reference.widths.put(column, width);
                }
                return "width " + column + " " + width;
            }
            default -> {
                tested.clear();
                reference.order = IntStream.range(0, modelCount).toArray();
                reference.hidden.clear();
                reference.widths.clear();
                return "clear";
            }
        }
    }

    private static void check(ColumnLayout tested, Reference reference, String context) {
        int[] visible = reference.visible();
        int modelCount = reference.order.length;
        assertEquals(modelCount, tested.modelCount(), context);
        assertEquals(visible.length, tested.count(), context);
        assertArrayEquals(reference.order, tested.order(), context);
        assertArrayEquals(reference.hidden.stream().mapToInt(Integer::intValue).toArray(), tested.hidden(), context);
        assertEquals(Arrays.equals(reference.order, IntStream.range(0, modelCount).toArray()),
                tested.isNaturalOrder(), context);
        int[] fromRuns = tested.runs().stream().flatMapToInt(run -> IntStream.rangeClosed(run[0], run[1])).toArray();
        assertArrayEquals(tested.isNaturalOrder() ? new int[0] : reference.order, fromRuns, context);
        List<int[]> runs = tested.runs();
        for (int i = 1; i < runs.size(); i++) {
            assertNotEquals(runs.get(i - 1)[1] + 1, runs.get(i)[0], "runs are merged, " + context);
        }
        double start = 0;
        for (int view = 0; view < visible.length; view++) {
            assertEquals(visible[view], tested.toModel(view), context);
            assertEquals(start, tested.start(view), 1e-9, context);
            assertEquals(view, tested.columnAt(start), context);
            start += reference.widths.getOrDefault(visible[view], 100.0);
        }
        assertEquals(start, tested.total(), 1e-9, context);
        for (int model = -1; model <= modelCount + 1; model++) {
            int expected = -1;
            for (int view = 0; view < visible.length; view++) {
                if (visible[view] == model) {
                    expected = view;
                }
            }
            assertEquals(expected, tested.toView(model), "toView(" + model + "), " + context);
            assertEquals(model < 0 || model >= modelCount || !reference.hidden.contains(model),
                    tested.isVisible(model), context);
        }
    }

    /** Bytes allocated by this thread. Reflective, as the module under test does not read java.management. */
    private static long allocatedBytes() throws ReflectiveOperationException {
        Object threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean")
                .invoke(null);
        return (long) Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes")
                .invoke(threads);
    }

    /** The order as an array of all columns and the hidden columns as a set, as simple as it gets. */
    private static final class Reference {

        int[] order = new int[0];
        final Set<Integer> hidden = new TreeSet<>();
        final Map<Integer, Double> widths = new HashMap<>();

        int[] visible() {
            return Arrays.stream(order).filter(column -> !hidden.contains(column)).toArray();
        }

        void move(int fromView, int toView) {
            if (fromView == toView) {
                return;
            }
            int[] visible = visible();
            int moved = visible[fromView];
            int target = visible[toView];
            List<Integer> rest = new ArrayList<>(Arrays.stream(order).boxed().toList());
            rest.remove((Integer) moved);
            rest.add(rest.indexOf(target) + (toView > fromView ? 1 : 0), moved);
            order = rest.stream().mapToInt(Integer::intValue).toArray();
        }

        void setVisible(int column, boolean visible) {
            if (visible) {
                hidden.remove(column);
            } else {
                hidden.add(column);
            }
        }

        void resize(int count) {
            int old = order.length;
            order = IntStream.concat(Arrays.stream(order).filter(column -> column < count), IntStream.range(old, count))
                    .toArray();
            hidden.removeIf(column -> column >= count);
            widths.keySet().removeIf(column -> column >= count);
        }
    }
}
