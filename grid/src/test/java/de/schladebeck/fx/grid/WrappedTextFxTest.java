package de.schladebeck.fx.grid;

import static de.schladebeck.fx.grid.Fx.layout;
import static de.schladebeck.fx.grid.Fx.mouse;
import static de.schladebeck.fx.grid.Fx.show;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.model.ComputedGridModel;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.CellStyle;
import de.schladebeck.fx.grid.render.TextMetrics;
import java.util.Collections;
import javafx.geometry.Bounds;
import javafx.geometry.HPos;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelReader;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.Font;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// [CellRenderers#WRAPPED_TEXT] in a grid: lines as the row height allows, cut-off text in the tooltip, line
/// breaks and alignment. Text is made of x's, which have no ascenders or descenders, so the painted lines can be
/// counted as bands of dark pixels.
class WrappedTextFxTest {

    /** Nine words, which need three lines in a 200 pixel column. */
    private static final String TEXT = String.join(" ", Collections.nCopies(9, "xxxxxxxx"));

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    /** A grid showing {@code text} in every row of one 200 pixel wide column with wrapped text. */
    private static Grid wrapped(String text) {
        Grid grid = new Grid(new ComputedGridModel(3, 1, (row, column) -> text));
        grid.getColumn(0).setWidth(200).setRenderer(CellRenderers.WRAPPED_TEXT);
        return show(grid);
    }

    /** The height that holds {@code lines} lines of the grid's font, as in the multi-line example of the README. */
    private static double heightFor(Grid grid, int lines) {
        return lines * TextMetrics.lineHeight(grid.getFont()) + 8;
    }

    /** Dark pixels in a view cell: how many bands of pixel rows hold some, and the leftmost and rightmost x. */
    private record Ink(int lines, int left, int right) {
    }

    private static Ink ink(Grid grid, int row, int column) {
        PixelReader pixels = grid.getParent().snapshot(null, null).getPixelReader();
        Bounds cell = grid.getCellBounds(row, column);
        int lines = 0;
        int left = Integer.MAX_VALUE;
        int right = -1;
        boolean inLine = false;
        for (int y = (int) cell.getMinY() + 1; y < cell.getMaxY() - 1; y++) {
            boolean dark = false;
            for (int x = (int) cell.getMinX() + 1; x < cell.getMaxX() - 1; x++) {
                if (pixels.getColor(x, y).getBrightness() < 0.5) {
                    dark = true;
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
            lines += dark && !inLine ? 1 : 0;
            inLine = dark;
        }
        return new Ink(lines, left, right);
    }

    @Test
    void wrappedTextTakesAsManyLinesAsTheRowHeightHolds() throws Exception {
        Fx.run(() -> {
            Grid grid = wrapped(TEXT);
            Font font = grid.getFont();
            int needed = TextMetrics.lineCount(TEXT, font, 200 - 2 * grid.getCellPadding());
            grid.setRowHeightProvider(row -> row == 0 ? heightFor(grid, needed) : 0);
            layout(grid);

            assertEquals(3, needed, "wraps at spaces");
            assertEquals(needed, ink(grid, 0, 0).lines(), "the lines TextMetrics counts");
            assertEquals(1, ink(grid, 1, 0).lines(), "a row of the default height holds one line");
        });
    }

    @Test
    void textNeedingMoreLinesThanTheRowHoldsIsCutWithTheFullTextInTheTooltip() throws Exception {
        Fx.run(() -> {
            Grid grid = wrapped(TEXT);
            grid.setRowHeightProvider(row -> heightFor(grid, row == 0 ? 2 : 3));
            layout(grid);
            Node canvas = grid.getChildrenUnmodifiable().getFirst();

            assertEquals(2, ink(grid, 0, 0).lines());
            Point2D cut = Fx.center(grid, 0, 0);
            mouse(grid, MouseEvent.MOUSE_MOVED, cut.getX(), cut.getY(), 0);
            assertEquals(TEXT, ((Tooltip) canvas.getProperties().get("javafx.scene.control.Tooltip")).getText());

            Point2D whole = Fx.center(grid, 1, 0);
            mouse(grid, MouseEvent.MOUSE_MOVED, whole.getX(), whole.getY(), 0);
            assertNull(canvas.getProperties().get("javafx.scene.control.Tooltip"), "a row with room for all");
        });
    }

    @Test
    void wrappedTextKeepsLineBreaksAndIsLeftAlignedUnlessTheStyleSaysOtherwise() throws Exception {
        Fx.run(() -> {
            Grid grid = wrapped("xx\nxx");
            grid.setRowHeightProvider(row -> heightFor(grid, 3));
            layout(grid);
            Bounds cell = grid.getCellBounds(0, 0);
            double padding = grid.getCellPadding();

            Ink left = ink(grid, 0, 0);
            assertEquals(2, left.lines(), "a line break starts a new line");
            assertTrue(left.left() < cell.getMinX() + padding + 3, left + " in " + cell);

            grid.setCellStyleProvider((row, column, value) -> CellStyle.DEFAULT.withAlignment(HPos.RIGHT));
            layout(grid);
            Ink right = ink(grid, 0, 0);
            assertEquals(2, right.lines());
            assertTrue(right.right() > cell.getMaxX() - padding - 4, right + " in " + cell);
        });
    }
}
