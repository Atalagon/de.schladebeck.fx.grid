package de.schladebeck.fx.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.schladebeck.fx.grid.internal.ColumnLayout;
import de.schladebeck.fx.grid.internal.Spans;
import javafx.geometry.Bounds;
import org.junit.jupiter.api.Test;

/// 1,000 rows of 20 px, 10 columns of 100 px, 2 frozen, 30 px column header, 40 px row header,
/// 600 x 400 canvas, scrolled by 150 / 200.
class GridGeometryTest {

    private final ColumnLayout columns = new ColumnLayout();
    private final GridGeometry g = new GridGeometry();

    GridGeometryTest() {
        columns.resize(10, 100);
        layout(1, 600, 400);
    }

    private void layout(double zoom, double canvasWidth, double canvasHeight) {
        Spans rows = new Spans();
        rows.setUniform(1_000, 20);
        g.update(columns, rows, 2, 0, 30, 40, zoom, canvasWidth, canvasHeight, 1);
        g.setScroll(150, 200);
    }

    @Test
    void visibleRange() {
        assertEquals(10, g.firstScrolledRow());
        assertEquals(28, g.lastScrolledRow());
        assertEquals(3, g.firstScrolledColumn());
        assertEquals(7, g.lastScrolledColumn());
    }

    @Test
    void hitTesting() {
        assertEquals(-1, g.rowAt(10), "column header");
        assertEquals(10, g.rowAt(35));
        assertEquals(-1, g.columnAt(20), "row header");
        assertEquals(0, g.columnAt(45), "frozen columns do not scroll");
        assertEquals(3, g.columnAt(250));
        assertEquals(3, g.resizeBorderAt(288));
        assertEquals(-1, g.resizeBorderAt(270));
        // Column 4 spans canvas x 290 to 390: left half drops before it, right half after it.
        assertEquals(4, g.dropGapAt(300));
        assertEquals(5, g.dropGapAt(380));
    }

    @Test
    void boundsAndHitTestingAgreeAtEveryZoom() {
        for (double zoom : new double[] {0.5, 1, 2, 4}) {
            layout(zoom, 600 * zoom, 400 * zoom);
            Bounds b = g.cellBounds(12, 3);
            assertEquals(190 * zoom, b.getMinX(), 1e-9);
            assertEquals(70 * zoom, b.getMinY(), 1e-9);
            assertEquals(12, g.rowAt(b.getCenterY()));
            assertEquals(3, g.columnAt(b.getCenterX()));
        }
    }

    @Test
    void frozenRowsStayAtTheTopAndTheOthersScrollBelowThem() {
        Spans rows = new Spans();
        rows.setUniform(1_000, 20);
        g.update(columns, rows, 2, 3, 30, 40, 1, 600, 400, 1);
        g.setScroll(0, 200);

        assertEquals(60, g.frozenHeight());
        assertEquals(2, g.lastFrozenRow());
        assertEquals(13, g.firstScrolledRow(), "the rows from 260 on, below the frozen 60 px");
        assertEquals(28, g.lastScrolledRow());
        assertEquals(1, g.rowAt(30 + 25));
        assertEquals(13, g.rowAt(30 + 61));
        assertEquals(50, g.cellBounds(1, 0).getMinY());
        assertEquals(90, g.cellBounds(13, 0).getMinY());
        assertEquals(200, g.scrollYToShow(1, 200), "frozen rows are always visible");
        assertEquals(200, g.scrollYToShow(13, 500));
        assertEquals(370 - 60, g.scrollableHeight());
    }

    @Test
    void scrollingIsClampedAndMinimal() {
        g.setScroll(1e9, 1e9);
        assertEquals(1_000 * 20 - 370, g.scrollY());
        assertEquals(1_000 - 200 - 360, g.scrollX());

        assertEquals(100 * 20 + 20 - 370, g.scrollYToShow(100, 200));
        assertEquals(100, g.scrollYToShow(5, 200));
        assertEquals(200, g.scrollYToShow(12, 200), "already visible");
        assertEquals(150, g.scrollXToShow(1, 150), "frozen column");
        assertEquals(0, g.scrollXToShow(2, 150));
    }
}
