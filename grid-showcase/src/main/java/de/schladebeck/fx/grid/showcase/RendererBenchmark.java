package de.schladebeck.fx.grid.showcase;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ComputedGridModel;
import de.schladebeck.fx.grid.render.CellContext;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRenderers;
import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.Scene;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

/// What drawing in a renderer costs: paints every visible cell, about 850 of them, with one renderer after another
/// while scrolling, with vsync and the 60 fps pulse cap switched off, and prints the time per frame of each. The
/// numbers are in the documentation of [CellRenderer].
public final class RendererBenchmark {

    private record Scenario(String name, CellRenderer renderer) {
    }

    private RendererBenchmark() {
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
            run(stage);
        }
    }

    private static void run(Stage stage) {
        List<Scenario> scenarios = scenarios();
        CellRenderer[] current = {scenarios.getFirst().renderer()};
        Grid grid = new Grid(new ComputedGridModel(1_000_000, 40, (row, column) -> "C" + row % 100));
        grid.setCellRendererProvider((row, column, value) -> current[0]);
        stage.setScene(new Scene(new StackPane(grid), 1280, 800));
        stage.setTitle("Renderer benchmark");
        Showcase.setIcon(stage);
        stage.show();
        for (int column = 0; column < 40; column++) {
            grid.getColumn(column).setWidth(50);
        }
        System.out.printf("%-38s %10s%n", "drawing in every cell", "ms/frame");

        new AnimationTimer() {
            private int index = -1;
            private boolean measuring;
            private long start;
            private long frames;

            @Override
            public void handle(long now) {
                double seconds = (now - start) / 1e9;
                if (index < 0 || seconds >= (measuring ? 2 : 1)) {
                    if (measuring) {
                        System.out.printf("%-38s %10.2f%n", scenarios.get(index).name(), seconds * 1000 / frames);
                    }
                    if (measuring || index < 0) {
                        if (++index == scenarios.size()) {
                            stop();
                            Platform.exit();
                            return;
                        }
                        current[0] = scenarios.get(index).renderer();
                        measuring = false;
                    } else {
                        measuring = true;
                    }
                    start = now;
                    frames = 0;
                }
                grid.setScrollY(grid.getScrollY() + 7);
                frames++;
            }
        }.start();
    }

    private static List<Scenario> scenarios() {
        WritableImage icon = new WritableImage(16, 16);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                icon.getPixelWriter().setColor(x, y, Color.hsb(x * 20, 0.6, 0.9));
            }
        }
        Font[] fonts = {Font.font("System", 12), Font.font("System", FontWeight.BOLD, 12),
                Font.font("System", FontPosture.ITALIC, 12)};
        double[] xs = new double[10];
        double[] ys = new double[10];
        return List.of(
                new Scenario("nothing", (gc, cell) -> { }),
                new Scenario("text", CellRenderers.TEXT),
                new Scenario("two rectangles", (gc, cell) -> {
                    gc.fillRect(cell.getX() + 4, cell.getY() + 4, 20, 8);
                    gc.fillRect(cell.getX() + 26, cell.getY() + 4, 10, 8);
                }),
                new Scenario("two lines", (gc, cell) -> {
                    gc.setLineWidth(2);
                    gc.strokeLine(cell.getX() + 4, cell.getY() + 4, cell.getX() + 12, cell.getY() + 14);
                    gc.strokeLine(cell.getX() + 12, cell.getY() + 14, cell.getX() + 24, cell.getY() + 4);
                }),
                new Scenario("oval", (gc, cell) -> gc.fillOval(cell.getX() + 4, cell.getY() + 4, 14, 14)),
                new Scenario("rounded rectangle", (gc, cell) ->
                        gc.fillRoundRect(cell.getX() + 4, cell.getY() + 4, 34, 14, 14, 14)),
                new Scenario("image made once", (gc, cell) -> gc.drawImage(icon, cell.getX() + 4, cell.getY() + 4)),
                new Scenario("text in three fonts taking turns", (gc, cell) -> {
                    gc.setFont(fonts[(cell.getViewRow() + cell.getViewColumn()) % 3]);
                    plainText(gc, cell);
                }),
                new Scenario("text inside a clip", (gc, cell) -> {
                    gc.save();
                    gc.beginPath();
                    gc.rect(cell.getX(), cell.getY(), cell.getWidth(), cell.getHeight());
                    gc.clip();
                    plainText(gc, cell);
                    gc.restore();
                }),
                new Scenario("triangle (fillPolygon)", (gc, cell) -> gc.fillPolygon(
                        new double[] {cell.getX() + 4, cell.getX() + 16, cell.getX() + 4},
                        new double[] {cell.getY() + 4, cell.getY() + 10, cell.getY() + 16}, 3)),
                new Scenario("two lines as a path", (gc, cell) -> {
                    gc.setLineWidth(2);
                    gc.beginPath();
                    gc.moveTo(cell.getX() + 4, cell.getY() + 4);
                    gc.lineTo(cell.getX() + 12, cell.getY() + 14);
                    gc.lineTo(cell.getX() + 24, cell.getY() + 4);
                    gc.stroke();
                }),
                new Scenario("polyline of 10 points", (gc, cell) -> {
                    for (int i = 0; i < 10; i++) {
                        xs[i] = cell.getX() + 4 + i * 4;
                        ys[i] = cell.getY() + 4 + (i * 7 % 9);
                    }
                    gc.strokePolyline(xs, ys, 10);
                }),
                new Scenario("text with a drop shadow", (gc, cell) -> {
                    gc.setEffect(new DropShadow(2, Color.GRAY));
                    plainText(gc, cell);
                }),
                new Scenario("snapshot of a Label per cell", (gc, cell) -> {
                    Label label = new Label(String.valueOf(cell.getValue()));
                    gc.drawImage(label.snapshot(null, null), cell.getX() + 4, cell.getY());
                }));
    }

    /** Text without the fitting of CellRenderers.TEXT, for the scenarios that change one thing about it. */
    private static void plainText(GraphicsContext gc, CellContext cell) {
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(String.valueOf(cell.getValue()), cell.getX() + 4, cell.getY() + cell.getHeight() / 2);
    }
}
