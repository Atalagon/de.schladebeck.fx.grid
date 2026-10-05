package de.schladebeck.fx.grid;

import static de.schladebeck.fx.grid.Fx.layout;
import static de.schladebeck.fx.grid.Fx.show;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.model.ComputedGridModel;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.CellStyle;
import de.schladebeck.fx.grid.render.TextMetrics;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.HPos;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Header styles and renderers, and fonts derived from a style's weight and posture. */
class HeaderAndFontFxTest {

    /** What a renderer saw. */
    record Seen(int row, int modelColumn, Object value, Font font, Color textFill, HPos alignment, double width) {
    }

    private final List<Seen> seen = new ArrayList<>();
    private final CellRenderer recording = (gc, cell) -> seen.add(new Seen(cell.getViewRow(), cell.getModelColumn(),
            cell.getValue(), cell.getFont(), cell.getTextFill(), cell.getAlignment(), cell.getWidth()));

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    @Test
    void columnGroupsAddHeaderRowsAboveTheColumnsInThem() throws Exception {
        Fx.run(() -> {
            Grid grid = grid();
            grid.setColumnGroups(List.of(
                    column -> column == 0 ? null : "Values",
                    column -> column < 2 ? null : "Late"));
            grid.getColumn(1).setHeaderRenderer(recording);
            show(grid);
            double group = grid.groupRowHeight();

            assertEquals(2 * group + grid.getHeaderHeight(), grid.columnHeaderHeight());
            assertEquals(0, grid.headerTop(0), "no group: the header reaches the top");
            assertEquals(group, grid.headerTop(1), "below its group");
            assertEquals(2 * group, grid.headerTop(2), "below both groups");
            double firstRow = grid.getCellBounds(0, 0).getMinY() - grid.getInsets().getTop();
            assertEquals(2 * group + grid.getHeaderHeight(), firstRow, 1e-9);
            assertTrue(seen.stream().anyMatch(s -> s.modelColumn() == 1), "the column's own header is painted");

            Fx.click(grid, Fx.center(grid, 0, 2).getX(), group / 2, 1);
            assertEquals(List.of(), grid.getSortKeys(), "a click on a group does nothing");
            Fx.click(grid, Fx.center(grid, 0, 2).getX(), 2 * group + grid.getHeaderHeight() / 2, 1);
            assertEquals(2, grid.getSortKeys().getFirst().column(), "a click below sorts its column");

            grid.setColumnGroups(List.of(column -> {
                throw new IllegalStateException("broken group");
            }));
            layout(grid);
            assertEquals(0, grid.headerTop(1), "a broken group counts as none");
        });
    }

    private static Grid grid() {
        return new Grid(new ComputedGridModel(20, 4, (row, column) -> row * 10 + column)
                .columnNames(column -> "Column " + column));
    }

    private Seen header(int modelColumn) {
        return seen.reversed().stream().filter(s -> s.row() == -1 && s.modelColumn() == modelColumn).findFirst()
                .orElseThrow(() -> new AssertionError("header " + modelColumn + " not painted"));
    }

    @Test
    void headersArePaintedByTheHeaderRendererWithTheirStyle() throws Exception {
        Fx.run(() -> {
            Grid grid = grid();
            grid.setHeaderRenderer(recording);
            grid.getColumn(1).setHeaderStyle(CellStyle.DEFAULT.withTextFill(Color.RED)
                    .withFontWeight(FontWeight.NORMAL).withAlignment(HPos.RIGHT));
            show(grid);

            assertEquals("Column 0", header(0).value());
            assertEquals(grid.getHeaderFont(), header(0).font());
            assertEquals(grid.getHeaderTextFill(), header(0).textFill());
            assertEquals(Color.RED, header(1).textFill());
            assertEquals(HPos.RIGHT, header(1).alignment());
            assertFalse(header(1).font().getStyle().contains("Bold"), "the style's weight wins");
            assertEquals(grid.getHeaderFont().getSize(), header(1).font().getSize());
        });
    }

    @Test
    void aColumnsOwnHeaderRendererWins() throws Exception {
        Fx.run(() -> {
            List<Integer> own = new ArrayList<>();
            Grid grid = grid();
            grid.setHeaderRenderer(recording);
            grid.getColumn(2).setHeaderRenderer((gc, cell) -> own.add(cell.getModelColumn()));
            show(grid);

            assertTrue(own.contains(2));
            assertTrue(seen.stream().noneMatch(s -> s.row() == -1 && s.modelColumn() == 2));
            assertNull(grid.getColumn(2).setHeaderRenderer(null).getHeaderRenderer(), "back to the grid's");
        });
    }

    @Test
    void theSortArrowKeepsItsRoomInACustomHeader() throws Exception {
        Fx.run(() -> {
            Grid grid = grid();
            grid.setHeaderRenderer(recording);
            grid.getColumn(0).setWidth(120);
            grid.getColumn(1).setWidth(120);
            show(grid);
            grid.sort(0, true);
            layout(grid);

            assertTrue(header(0).width() < header(1).width() - 8, "the sorted header is narrower for the arrow");
        });
    }

    @Test
    void fittingMeasuresTheHeaderRenderer() throws Exception {
        Fx.run(() -> {
            Grid grid = grid();
            grid.getColumn(3).setHeaderRenderer((gc, cell) ->
                    CellRenderers.drawText(gc, cell, cell.getValue() + " with a long explanation", HPos.LEFT));
            show(grid);

            assertTrue(grid.getColumn(3).getWidth() > grid.getColumn(2).getWidth() + 80);
        });
    }

    @Test
    void textInAnotherFontIsFittedAndMeasured() throws Exception {
        Fx.run(() -> {
            String caption = "a caption much longer than the column name";
            Font small = Font.font(Font.getDefault().getFamily(), 9);
            List<String> fitted = new ArrayList<>();
            Grid grid = grid();
            grid.getColumn(3).setHeaderRenderer((gc, cell) -> {
                cell.fitText((String) cell.getValue());
                fitted.add(cell.fitText(caption, small));
            });
            show(grid);
            double captionWidth = TextMetrics.width(caption, small) + 2 * grid.getCellPadding();

            assertTrue(grid.getColumn(3).getWidth() >= captionWidth, "fitting counts the caption");
            assertEquals(caption, fitted.getLast());

            grid.getColumn(3).setWidth(60);
            layout(grid);
            assertTrue(fitted.getLast().endsWith("…"), fitted.getLast());
            assertTrue(TextMetrics.width(fitted.getLast(), small) <= 60 - 2 * grid.getCellPadding());
        });
    }

    @Test
    void weightAndPostureFollowTheGridFont() throws Exception {
        Fx.run(() -> {
            Grid grid = grid();
            CellStyle boldItalic = CellStyle.DEFAULT.withFontWeight(FontWeight.BOLD)
                    .withFontPosture(FontPosture.ITALIC);
            grid.setCellStyleProvider((row, column, value) -> column == 1 ? boldItalic : null);
            grid.getColumn(1).setRenderer(recording);
            show(grid);
            Font first = seen.getLast().font();

            grid.setFont(Font.font(grid.getFont().getFamily(), 20));
            seen.clear();
            layout(grid);
            Font larger = seen.getLast().font();

            assertEquals(grid.getFont().getFamily(), first.getFamily());
            assertTrue(first.getStyle().contains("Bold") && first.getStyle().contains("Italic"), first.getStyle());
            assertEquals(20, larger.getSize());
            assertTrue(larger.getStyle().contains("Bold"), larger.getStyle());
        });
    }
}
