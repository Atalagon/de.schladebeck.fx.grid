package de.schladebeck.fx.grid.showcase;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.CellStyle;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

/// Scrolls a grid of 100,000,000 rows x 1,000 columns through several scenarios, shows the frame rate while it
/// runs, prints frames per second per scenario and exits. Vsync and the 60 fps pulse cap are switched off, so the
/// numbers show what the grid could do.
///
/// The grid is styled like a real application: frozen columns, a row header, a formatting renderer, cell styles
/// with other fonts, and column headers with two lines and a colored stripe. `--style=plain` leaves all of that
/// out, to see what it costs.
///
/// Arguments: `--width=1280 --height=800` (window size in logical pixels), `--rows=100000000`,
/// `--columns=1000`, `--style=styled|plain`. For a wide grid use `--rows=1000 --columns=100000000`.
/// The window's scaling follows the screen; add `-Dglass.win.uiScale=1.0` to measure at 100%.
public final class ScrollBenchmark {

    private static final double WARMUP_SECONDS = 1.5;
    private static final double MEASURE_SECONDS = 3;
    private static final double OVERLAY_SECONDS = 0.25;

    /** Per frame: scroll by dx, dy pixels and fire this many cell updates. */
    private record Scenario(String name, double zoom, double dx, double dy, int updates) {
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("vertical scroll", 1, 0, 7, 0),
            new Scenario("horizontal scroll", 1, 7, 0, 0),
            new Scenario("diagonal scroll", 1, 4, 7, 0),
            new Scenario("1,000 rows per frame", 1, 0, 24_000, 0),
            new Scenario("1,000 columns per frame", 1, 100_000, 0, 0),
            new Scenario("vertical scroll, zoom 50%", 0.5, 0, 7, 0),
            new Scenario("vertical scroll, zoom 200%", 2, 0, 7, 0),
            new Scenario("1,000 cell updates per frame", 1, 0, 0, 1_000));

    private ScrollBenchmark() {
    }

    /// Not an Application itself: for a main class extending Application, the Java launcher starts the
    /// toolkit before main runs, and these properties are read at toolkit start.
    public static void main(String[] args) {
        System.setProperty("javafx.animation.fullspeed", "true");
        System.setProperty("prism.vsync", "false");
        Application.launch(App.class, args);
    }

    public static final class App extends Application {

        @Override
        public void start(Stage stage) {
            run(stage, getParameters().getNamed());
        }
    }

    private static void run(Stage stage, Map<String, String> args) {
        double width = Double.parseDouble(args.getOrDefault("width", "1280"));
        double height = Double.parseDouble(args.getOrDefault("height", "800"));
        int rows = Integer.parseInt(args.getOrDefault("rows", "100000000"));
        int columns = Integer.parseInt(args.getOrDefault("columns", "1000"));
        boolean styled = !args.getOrDefault("style", "styled").equals("plain");

        SyntheticModel model = new SyntheticModel(rows, columns);
        Grid grid = new Grid(model);
        if (styled) {
            style(grid, model);
        }
        Label overlay = new Label();
        overlay.setMouseTransparent(true);
        overlay.setStyle("-fx-background-color: rgba(0, 0, 0, 0.72); -fx-background-radius: 6; -fx-padding: 6 10;"
                + " -fx-text-fill: white; -fx-font-family: 'Consolas', 'Menlo', monospace;");
        StackPane root = new StackPane(grid, overlay);
        StackPane.setAlignment(overlay, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(overlay, new Insets(0, 30, 30, 0));

        stage.setScene(new Scene(root, width, height));
        stage.setTitle("Scroll benchmark");
        Showcase.setIcon(stage);
        stage.show();
        double scale = stage.getOutputScaleX();
        System.out.printf("Grid: %,d rows x %,d columns = %,d cells, %d nodes in the grid, %s%n",
                rows, columns, (long) rows * columns, countNodes(grid), styled ? "styled" : "plain");
        System.out.printf("Viewport %.0fx%.0f logical pixels at %.0f%% scaling (%dx%d physical)%n%n",
                width, height, scale * 100, Math.round(width * scale), Math.round(height * scale));
        System.out.printf("%-30s %8s %10s%n", "scenario", "fps", "ms/frame");

        new AnimationTimer() {
            private int index = -1;
            private boolean measuring;
            private long phaseStart;
            private long frames;
            private long overlayStart;
            private long overlayFrames;

            @Override
            public void handle(long now) {
                if (index < 0 || phaseOver()) {
                    if (measuring) {
                        double fps = frames / elapsed();
                        System.out.printf("%-30s %8.0f %10.2f%n", SCENARIOS.get(index).name(), fps, 1000 / fps);
                    }
                    if (measuring || index < 0) {
                        if (++index == SCENARIOS.size()) {
                            stop();
                            Platform.exit();
                            return;
                        }
                        grid.setZoom(SCENARIOS.get(index).zoom());
                        grid.setScrollX(0);
                        grid.setScrollY(0);
                        measuring = false;
                    } else {
                        measuring = true;
                    }
                    phaseStart = System.nanoTime();
                    frames = 0;
                }

                Scenario scenario = SCENARIOS.get(index);
                grid.setScrollX(grid.getScrollX() + scenario.dx());
                grid.setScrollY(grid.getScrollY() + scenario.dy());
                for (int i = 0; i < scenario.updates(); i++) {
                    model.touch(i % 50);
                }
                frames++;
                showFrameRate(now, scenario);
            }

            /** Frames per second over the last quarter second, in the overlay. */
            private void showFrameRate(long now, Scenario scenario) {
                overlayFrames++;
                double seconds = (now - overlayStart) / 1e9;
                if (seconds >= OVERLAY_SECONDS) {
                    double fps = overlayFrames / seconds;
                    overlay.setText(String.format("%d/%d  %s%n%-10s  %5.0f fps  %5.2f ms", index + 1,
                            SCENARIOS.size(), scenario.name(), measuring ? "measuring" : "warm-up", fps, 1000 / fps));
                    overlayStart = now;
                    overlayFrames = 0;
                }
            }

            private boolean phaseOver() {
                return elapsed() >= (measuring ? MEASURE_SECONDS : WARMUP_SECONDS);
            }

            private double elapsed() {
                return (System.nanoTime() - phaseStart) / 1e9;
            }
        }.start();
    }

    /// Frozen columns, a row header, a formatting renderer, cell styles with other fonts and styled column
    /// headers, like a real application.
    private static void style(Grid grid, SyntheticModel model) {
        grid.setRowHeaderVisible(true);
        grid.setFrozenColumnCount(2);
        grid.getColumn(0).setWidth(100);
        grid.getColumn(1).setWidth(160);
        grid.getColumn(2).setRenderer(CellRenderers.format(new DecimalFormat("#,##0.00"), HPos.RIGHT));

        // Fonts that depend on the grid's font are made when CSS sets it, once per font, not per cell.
        ObjectBinding<CellStyle> id = Bindings.createObjectBinding(
                () -> CellStyle.DEFAULT.withFont(Font.font("Monospaced", grid.getFont().getSize())),
                grid.fontProperty());
        ObjectBinding<Font> caption = Bindings.createObjectBinding(() -> Font.font(grid.getHeaderFont().getFamily(),
                FontPosture.ITALIC, grid.getHeaderFont().getSize() - 2), grid.headerFontProperty());
        CellStyle vip = CellStyle.DEFAULT.withFontWeight(FontWeight.BOLD);
        CellStyle longText = CellStyle.DEFAULT.withFontPosture(FontPosture.ITALIC).withTextFill(Color.web("#6e7781"));
        CellStyle negative = CellStyle.DEFAULT.withTextFill(Color.web("#c62828")).withFontWeight(FontWeight.BOLD);
        CellStyle high = CellStyle.DEFAULT.withBackground(Color.web("#fff3cd")).withFontWeight(FontWeight.BOLD);
        grid.setCellStyleProvider((row, column, value) -> switch (value) {
            case Integer i when column == 0 -> id.get();
            case String s when column == 1 -> row % 5 == 0 ? vip : null;
            case String s -> s.length() > 20 ? longText : null;
            case Double d -> d < 0 ? negative : d > 9_000 ? high : null;
            case null, default -> null;
        });

        CellStyle frozenHeader = CellStyle.DEFAULT.withBackground(Color.web("#24292f")).withTextFill(Color.WHITE);
        grid.getColumn(0).setHeaderStyle(frozenHeader);
        grid.getColumn(1).setHeaderStyle(frozenHeader);
        grid.setStyle("-grid-header-height: 4em;");
        grid.setHeaderRenderer(typedHeader(model, caption));
    }

    /// Two lines per column header: the name, and below it the column's type in small italics above a stripe in
    /// the type's color. Numbers get right-aligned headers. One renderer for all columns, so it also works for
    /// 100 million of them.
    private static CellRenderer typedHeader(SyntheticModel model, ObjectBinding<Font> captionFont) {
        Map<Class<?>, String> types = Map.of(Integer.class, "integer", String.class, "text", Double.class, "decimal",
                Boolean.class, "yes or no");
        Map<Class<?>, Color> colors = Map.of(Integer.class, Color.web("#0969da"), String.class, Color.web("#1a7f37"),
                Double.class, Color.web("#bc4c00"), Boolean.class, Color.web("#8250df"));
        return (gc, cell) -> {
            Class<?> type = model.getColumnClass(cell.getModelColumn());
            double bottom = cell.getY() + cell.getHeight();
            gc.setFill(colors.get(type));
            gc.fillRect(cell.getX(), bottom - 3, cell.getWidth(), 3);

            boolean number = type == Integer.class || type == Double.class;
            HPos alignment = cell.getAlignment() != null ? cell.getAlignment() : number ? HPos.RIGHT : HPos.LEFT;
            double x = alignment == HPos.RIGHT ? cell.getX() + cell.getWidth() - cell.getPadding()
                    : cell.getX() + cell.getPadding();
            gc.setTextAlign(alignment == HPos.RIGHT ? TextAlignment.RIGHT : TextAlignment.LEFT);
            gc.setFill(cell.getTextFill());
            gc.fillText(cell.fitText((String) cell.getValue()), x, cell.getY() + cell.getHeight() * 0.33);
            Font caption = captionFont.get();
            gc.setFont(caption);
            gc.setFill(cell.getTextFill().deriveColor(0, 1, 1, 0.65));
            gc.fillText(cell.fitText(types.get(type), caption), x, cell.getY() + cell.getHeight() * 0.68);
        };
    }

    /** The node and all its descendants. */
    static int countNodes(Node node) {
        int count = 1;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                count += countNodes(child);
            }
        }
        return count;
    }
}
