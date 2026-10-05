package de.schladebeck.fx.grid.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.Fx;
import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ListGridModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// What the bars, labels and charts of [CellRenderers] paint, sampled from the pixels of a cell 200 x 24 painted
/// on white with a test context (padding 6), or of a grid.
class CellRenderersFxTest {

    public record Amount(double withBar, double without) {
    }

    public record Task(double progress, String status, List<Integer> trend) {
    }

    private static final int WIDTH = 200;

    private final Locale before = Locale.getDefault();

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(before);
    }

    private static PixelReader paint(CellRenderer renderer, Object value) {
        Canvas canvas = new Canvas(WIDTH, 24);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, WIDTH, 24);
        CellRenderersTest.TestContext cell = new CellRenderersTest.TestContext(value);
        cell.width = WIDTH;
        renderer.paint(gc, cell);
        return canvas.snapshot(null, null).getPixelReader();
    }

    private static boolean close(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen())
                + Math.abs(a.getBlue() - b.getBlue()) < 0.12;
    }

    /** The brightness of the pixels from {@code fromX} to {@code toX}, top to bottom. */
    private static DoubleSummaryStatistics brightness(PixelReader pixels, double fromX, double toX) {
        DoubleSummaryStatistics stats = new DoubleSummaryStatistics();
        for (int x = (int) fromX; x < toX; x++) {
            for (int y = 0; y < 24; y++) {
                stats.accept(pixels.getColor(x, y).getBrightness());
            }
        }
        return stats;
    }

    /** The rows where pixel column {@code x} is not white. */
    private static List<Integer> ink(PixelReader pixels, int x) {
        List<Integer> rows = new ArrayList<>();
        for (int y = 0; y < 24; y++) {
            if (!close(Color.WHITE, pixels.getColor(x, y))) {
                rows.add(y);
            }
        }
        return rows;
    }

    /** The brightness of the darkest pixel of a grid cell. */
    private static double darkest(PixelReader pixels, Bounds cell) {
        double darkest = 1;
        for (int x = (int) cell.getMinX() + 1; x < cell.getMaxX() - 1; x++) {
            for (int y = (int) cell.getMinY() + 1; y < cell.getMaxY() - 1; y++) {
                darkest = Math.min(darkest, pixels.getColor(x, y).getBrightness());
            }
        }
        return darkest;
    }

    @Test
    void progressBarsAreClampedAndEndBeforeThePercentage() throws Exception {
        Locale.setDefault(Locale.US);
        Fx.run(() -> {
            Font font = Font.getDefault();
            double bar = WIDTH - 12 - font.getSize() / 2 - TextMetrics.width("100%", font);
            Color track = Color.WHITE.interpolate(Color.RED, 0.15);
            CellRenderer red = CellRenderers.progress(Color.RED);
            PixelReader half = paint(red, 0.5);
            assertTrue(close(Color.RED, half.getColor((int) (6 + bar / 4), 12)));
            assertTrue(close(track, half.getColor((int) (6 + bar * 3 / 4), 12)));
            assertTrue(close(Color.WHITE, half.getColor((int) (6 + bar + 2), 12)), "a gap before the text");
            assertTrue(brightness(half, 6 + bar + 4, WIDTH - 6).getMin() < 0.4, "the percentage");
            assertTrue(close(Color.RED, paint(red, 1.5).getColor((int) (6 + bar - 6), 12)), "clamped to 1");
            assertTrue(close(track, paint(red, -0.5).getColor((int) (6 + bar / 2), 12)), "clamped to 0");
        });
    }

    @Test
    void badgeTextIsLightOnDarkColorsAndDarkOnLightOnes() throws Exception {
        Fx.run(() -> {
            Font font = Font.getDefault();
            double height = Math.min(22, Math.round(font.getSize() * 1.6));
            double end = 6 + TextMetrics.width("Blocked", font) + height;
            Color blue = Color.web("#1d4ed8");
            PixelReader dark = paint(CellRenderers.badge((String s) -> blue), "Blocked");
            assertTrue(close(blue, dark.getColor((int) (6 + height / 4), 12)), "the round end");
            assertTrue(brightness(dark, 6 + height / 2, end - height / 2).getMax() > 0.9, "white text");
            assertTrue(close(Color.WHITE, dark.getColor((int) end + 2, 12)), "as wide as the text");
            PixelReader light = paint(CellRenderers.badge((String s) -> Color.web("#dbeafe")), "Blocked");
            assertTrue(brightness(light, 6 + height / 2, end - height / 2).getMin() < 0.3, "dark text");
        });
    }

    @Test
    void sparklinesSpanTheCellAndMarkTheLastValue() throws Exception {
        Fx.run(() -> {
            double size = Font.getDefault().getSize();
            double dot = Math.max(1.5, size / 6);
            double top = dot + (24 - Math.min(24, size * 1.6)) / 2;
            CellRenderer red = CellRenderers.sparkline(Color.RED);
            PixelReader rising = paint(red, new double[] {0, 1});
            assertTrue(close(Color.RED, rising.getColor(WIDTH - 6, (int) top)), "the dot at the last value");
            for (PixelReader pixels : List.of(rising, paint(red, List.of(2, 2, 2)))) {
                List<Integer> middle = ink(pixels, WIDTH / 2);
                assertFalse(middle.isEmpty());
                assertTrue(middle.stream().allMatch(y -> y >= 10 && y <= 14), middle.toString());
            }
            assertTrue(ink(rising, 10).stream().allMatch(y -> y > 16), "starts at the bottom");
        });
    }

    @Test
    void missingOrWrongValuesLeaveTheCellEmpty() throws Exception {
        Fx.run(() -> {
            List<CellRenderer> renderers = List.of(CellRenderers.PROGRESS, CellRenderers.dataBar(0, 1, Color.RED),
                    CellRenderers.sparkline(Color.RED));
            for (CellRenderer renderer : renderers) {
                for (Object value : Arrays.asList(null, "text", new double[0], new double[] {1, Double.NaN},
                        List.of(1, "two"))) {
                    assertEquals(1, brightness(paint(renderer, value), 0, WIDTH).getMin(), 1e-9, String.valueOf(value));
                }
            }
            assertEquals(1, brightness(paint(CellRenderers.badge((String s) -> Color.RED), null), 0, WIDTH).getMin());
        });
    }

    @Test
    void dataBarsTintTheCellAndTheSelectionAndKeepTheNumberReadable() throws Exception {
        Fx.run(() -> {
            Grid grid = new Grid(ListGridModel.ofRecords(Amount.class, List.of(new Amount(100, 100),
                    new Amount(100, 100))));
            grid.getColumn(0).setRenderer(CellRenderers.dataBar(0, 100, Color.BLUE));
            Fx.show(grid);
            grid.getSelectionModel().select(1, 0);
            grid.getSelectionModel().extend(1, 1);
            Fx.layout(grid);
            PixelReader pixels = grid.getParent().snapshot(null, null).getPixelReader();
            for (int row = 0; row < 2; row++) {
                Bounds withBar = grid.getCellBounds(row, 0);
                Bounds without = grid.getCellBounds(row, 1);
                Color bar = pixels.getColor((int) withBar.getMinX() + 6, (int) withBar.getCenterY());
                Color plain = pixels.getColor((int) without.getMinX() + 6, (int) without.getCenterY());
                assertTrue(bar.getRed() < plain.getRed() - 0.2 && bar.getBlue() > plain.getBlue() - 0.05,
                        row + ": " + bar + " on " + plain);
                assertEquals(darkest(pixels, without), darkest(pixels, withBar), 0.05, "the number on the bar");
            }
        });
    }

    @Test
    void fittingAColumnMakesRoomForTheBarTheLabelAndTheChart() throws Exception {
        Locale.setDefault(Locale.US);
        Fx.run(() -> {
            Grid grid = new Grid(ListGridModel.ofRecords(Task.class, List.of(new Task(1, "Blocked", List.of(3, 1)))));
            grid.getColumn(0).setRenderer(CellRenderers.PROGRESS);
            grid.getColumn(1).setRenderer(CellRenderers.badge((String s) -> Color.web("#fee2e2")));
            grid.getColumn(2).setRenderer(CellRenderers.sparkline(Color.BLUE));
            Fx.show(grid);
            Font font = grid.getFont();
            double size = font.getSize();
            double[] content = {TextMetrics.width("100%", font) + 4.5 * size,
                    TextMetrics.width("Blocked", font) + Math.min(grid.getRowHeight() - 3, Math.round(size * 1.6)),
                    6 * size};
            for (int column = 0; column < content.length; column++) {
                // The paddings, the grid line and up to one pixel for snapping, rounded up.
                assertEquals(content[column] + 2 * grid.getCellPadding() + 2,
                        grid.getColumn(column).fitToContent().getWidth(), 1, "column " + column);
            }
        });
    }
}
