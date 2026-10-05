package de.schladebeck.fx.grid.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SpansTest {

    private final Spans layout = new Spans();

    @Test
    void uniformRowsNeedNoExceptions() {
        layout.setUniform(100_000_000, 24);

        assertEquals(2.4e9, layout.total());
        assertEquals(24 * 73_456_789.0, layout.start(73_456_789));
        assertEquals(73_456_789, layout.indexAt(24 * 73_456_789.0 + 23.9));
        assertEquals(-1, layout.indexAt(-1));
        assertEquals(-1, layout.indexAt(2.4e9));
    }

    @Test
    void tallRowsShiftEverythingBelow() {
        // rows 3 and 7 are 60 px high, the rest 20
        layout.set(10, 20, new int[] {3, 7}, new double[] {60, 60}, 2);

        assertEquals(10 * 20 + 2 * 40, layout.total());
        assertEquals(60, layout.start(3));
        assertEquals(60, layout.size(3));
        assertEquals(120, layout.start(4));
        assertEquals(20, layout.size(4));
        assertEquals(180, layout.start(7));
        assertEquals(240, layout.start(8));
        assertEquals(layout.total(), layout.start(10));
    }

    @Test
    void findsRowsInsideTallAndNormalRows() {
        layout.set(10, 20, new int[] {3, 7}, new double[] {60, 60}, 2);

        assertEquals(2, layout.indexAt(59.9));
        assertEquals(3, layout.indexAt(60));
        assertEquals(3, layout.indexAt(119.9));
        assertEquals(4, layout.indexAt(120));
        assertEquals(6, layout.indexAt(179.9));
        assertEquals(7, layout.indexAt(239.9));
        assertEquals(9, layout.indexAt(279.9));
        for (int row = 0; row < 10; row++) {
            assertEquals(row, layout.indexAt(layout.start(row)), "top of row " + row);
            assertEquals(row, layout.indexAt(layout.start(row + 1) - 0.01), "bottom of row " + row);
        }
    }

    @Test
    void insertedAndRemovedItemsMoveTheOthers() {
        Random random = new Random(3);
        List<Double> sizes = new ArrayList<>();
        layout.setUniform(0, 20);
        for (int step = 0; step < 2_000; step++) {
            int index = random.nextInt(sizes.size() + 1);
            int n = 1 + random.nextInt(4);
            if (random.nextBoolean() || sizes.size() < n) {
                layout.inserted(index, n);
                for (int i = 0; i < n; i++) {
                    sizes.add(index, 20.0);
                }
                double size = 10 + random.nextInt(3) * 10;
                layout.setSize(index, size);
                sizes.set(index, size);
            } else {
                index = Math.min(index, sizes.size() - n);
                layout.removed(index, n);
                sizes.subList(index, index + n).clear();
            }
            double start = 0;
            for (int i = 0; i < sizes.size(); i++) {
                assertEquals(start, layout.start(i), 1e-9, "start of item " + i + " after step " + step);
                start += sizes.get(i);
            }
            assertEquals(sizes.size(), layout.count());
            assertEquals(start, layout.total(), 1e-9);
        }
    }
}
