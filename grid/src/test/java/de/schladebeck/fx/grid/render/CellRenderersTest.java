package de.schladebeck.fx.grid.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.schladebeck.fx.grid.Fx;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javafx.geometry.HPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Renderers tested without a grid, through a context of their own: how a renderer test can look.
class CellRenderersTest {

    /// A cell 100 x 24 at the origin that records what is fitted and, as the grid does when it fits a column, the
    /// width the cell wants. Cuts text after `room` characters.
    static final class TestContext extends CellContext {
        final List<String> fitted = new ArrayList<>();
        Object value;
        int room = Integer.MAX_VALUE;
        int fractionDigits;
        HPos alignment;
        double x;
        double width = 100;
        double indent;
        double wanted;

        TestContext(Object value) {
            this.value = value;
        }

        @Override public int getViewRow() { return 0; }
        @Override public int getViewColumn() { return 0; }
        @Override public int getModelRow() { return 0; }
        @Override public int getModelColumn() { return 0; }
        @Override public Object getValue() { return value; }
        @Override public double getX() { return x; }
        @Override public double getY() { return 0; }
        @Override public double getWidth() { return width; }
        @Override public double getHeight() { return 24; }
        @Override public double getPadding() { return 6; }
        @Override public boolean isSelected() { return false; }
        @Override public boolean isFocused() { return false; }
        @Override public Font getFont() { return Font.getDefault(); }
        @Override public Color getTextFill() { return Color.BLACK; }
        @Override public HPos getAlignment() { return alignment; }
        @Override public List<String> wrapText(String text) { return List.of(text); }
        @Override public double getLineHeight() { return 16; }
        @Override public int getFractionDigits() { return fractionDigits; }

        @Override
        public void indent(double width) {
            x += width;
            this.width -= width;
            indent += width;
            wanted = Math.max(wanted, 2 * getPadding() + indent); // like the grid: room taken counts when fitting
        }

        @Override
        public String fitText(String text, Font font) {
            fitted.add(text);
            wanted = Math.max(wanted, TextMetrics.width(text, font) + 2 * getPadding() + indent);
            return text.length() <= room ? text : text.substring(0, room) + "…";
        }
    }

    private final Locale before = Locale.getDefault();
    private final Canvas canvas = new Canvas(100, 24);

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(before);
    }

    private TestContext paint(CellRenderer renderer, Object value) {
        TestContext cell = new TestContext(value);
        renderer.paint(canvas.getGraphicsContext2D(), cell);
        return cell;
    }

    @Test
    void decimalsInTheLocaleWholeNumbersAsTheyAre() {
        Locale.setDefault(Locale.GERMANY);
        assertEquals(List.of("3.703.703,673"), paint(CellRenderers.NUMBER, 3703703.6730000004).fitted);
        assertEquals(List.of("0,3"), paint(CellRenderers.NUMBER, 0.1 + 0.2).fitted);
        assertEquals(List.of("12,50"), paint(CellRenderers.NUMBER, new BigDecimal("12.50")).fitted);
        assertEquals(List.of("19870101"), paint(CellRenderers.NUMBER, 19870101).fitted, "no grouping for IDs");
        assertEquals(List.of("1.0E-7"), paint(CellRenderers.NUMBER, 1e-7).fitted, "tiny numbers stay readable");
    }

    @Test
    void decimalsGetTheFractionDigitsTheColumnNeeds() {
        Locale.setDefault(Locale.GERMANY);
        for (Object value : List.of(48065.7, 3.0, new BigDecimal("12.5"), 7)) {
            TestContext cell = new TestContext(value);
            cell.fractionDigits = 2;
            CellRenderers.NUMBER.paint(canvas.getGraphicsContext2D(), cell);
            assertEquals(switch (value) {
                case Integer i -> "7"; // whole numbers stay as they are
                case BigDecimal b -> "12,50";
                default -> value.equals(3.0) ? "3,00" : "48.065,70";
            }, cell.fitted.getFirst());
        }
    }

    @Test
    void numbersThatDoNotFitAreNeverShortened() {
        TestContext cell = new TestContext(123456789.5);
        cell.room = 4;
        CellRenderers.NUMBER.paint(canvas.getGraphicsContext2D(), cell);
        assertEquals(1, cell.fitted.size(), "the full number is offered once, for the tooltip");
    }

    @Test
    void textIsOneLineAndFormatsAreUsed() {
        assertEquals(List.of("first second"), paint(CellRenderers.TEXT, "first\nsecond").fitted);
        assertEquals(List.of("12.50"), paint(CellRenderers.number("#,##0.00", Locale.US), 12.5).fitted);
        assertEquals(List.of("NAME"), paint(CellRenderers.text((String s) -> s.toUpperCase()), "name").fitted);
    }

    /** Runs a renderer as fitting a column does: in a cell with room for anything. */
    private TestContext measure(CellRenderer renderer, Object value) {
        TestContext cell = new TestContext(value);
        cell.width = 10_000;
        renderer.paint(canvas.getGraphicsContext2D(), cell);
        return cell;
    }

    @Test
    void progressWritesThePercentageInTheLocaleWithoutClampingIt() {
        Locale.setDefault(Locale.GERMANY);
        assertEquals(List.of("45 %"), paint(CellRenderers.PROGRESS, 0.45).fitted);
        Locale.setDefault(Locale.US);
        assertEquals(List.of("120%"), paint(CellRenderers.progress(Color.GREEN), 1.2).fitted);
        assertEquals(List.of("-5%"), paint(CellRenderers.PROGRESS, -0.05).fitted);
    }

    @Test
    void dataBarsWriteNumbersAsNumberDoes() {
        Locale.setDefault(Locale.GERMANY);
        TestContext cell = new TestContext(3.0);
        cell.fractionDigits = 2;
        CellRenderers.dataBar(0, 10, Color.BLUE).paint(canvas.getGraphicsContext2D(), cell);
        assertEquals(List.of("3,00"), cell.fitted);
        assertEquals(List.of("19870101"), paint(CellRenderers.dataBar(0, 1, Color.BLUE), 19870101).fitted);
        assertThrows(IllegalArgumentException.class, () -> CellRenderers.dataBar(1, 1, Color.BLUE));
    }

    @Test
    void badgesShowTheValueOrATextComputedFromIt() {
        assertEquals(List.of("done"), paint(CellRenderers.badge((String s) -> Color.GREEN), "done").fitted);
        assertEquals(List.of("DONE"), paint(CellRenderers.badge((String s) -> s.toUpperCase(Locale.ROOT),
                (String s) -> Color.GREEN), "done").fitted);
        assertEquals(List.of("done"), paint(CellRenderers.badge((String s) -> null), "done").fitted,
                "without a color the text stays");
    }

    @Test
    void barsLabelsAndChartsPaintNothingForMissingOrWrongValues() {
        List<CellRenderer> renderers = List.of(CellRenderers.PROGRESS, CellRenderers.dataBar(0, 1, Color.BLUE),
                CellRenderers.sparkline(Color.BLUE));
        List<Object> values = Arrays.asList(null, "text", new double[0], new double[] {1, Double.NaN},
                List.of(1, "two"), List.of(1, Double.POSITIVE_INFINITY));
        for (CellRenderer renderer : renderers) {
            for (Object value : values) {
                TestContext cell = paint(renderer, value);
                assertEquals(List.of(), cell.fitted, String.valueOf(value));
                assertEquals(0, cell.indent);
            }
        }
        assertEquals(List.of(), paint(CellRenderers.badge((String s) -> Color.GREEN), null).fitted);
        assertEquals(List.of(), paint(CellRenderers.badge((String s) -> null, (String s) -> Color.GREEN), "x").fitted);
        // As for text(): the grid reports this once and leaves the cell empty.
        assertThrows(ClassCastException.class, () -> paint(CellRenderers.badge((String s) -> Color.GREEN), 42));
    }

    @Test
    void fittingTheColumnCountsBarsLabelsAndCharts() {
        Locale.setDefault(Locale.US);
        Font font = Font.getDefault();
        double size = font.getSize();
        double paddings = 12;
        assertEquals(TextMetrics.width("45%", font) + paddings + 4 * size + size / 2,
                measure(CellRenderers.PROGRESS, 0.45).wanted, 1e-9, "a bar four times the font size and a gap");
        assertEquals(TextMetrics.width("1,234.5", font) + paddings,
                measure(CellRenderers.dataBar(0, 1, Color.BLUE), 1234.5).wanted, 1e-9, "the bar is behind the text");
        assertEquals(TextMetrics.width("done", font) + paddings + Math.min(22, Math.round(size * 1.6)),
                measure(CellRenderers.badge((String s) -> Color.GREEN), "done").wanted, 1e-9, "the round ends");
        assertEquals(paddings + 6 * size, measure(CellRenderers.sparkline(Color.BLUE), List.of(1, 3, 2)).wanted,
                1e-9, "six times the font size");
    }
}
