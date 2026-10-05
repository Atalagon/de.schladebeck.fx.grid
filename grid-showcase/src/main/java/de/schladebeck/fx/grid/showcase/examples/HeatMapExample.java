package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ComputedGridModel;
import de.schladebeck.fx.grid.render.CellStyle;
import java.util.List;
import java.util.stream.IntStream;
import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;

public final class HeatMapExample extends Example {

    private static final int SIZE = 300;
    /** 32 shared styles from blue (-1) to red (+1); the style provider only picks one. */
    private static final List<CellStyle> HEAT = IntStream.range(0, 32)
            .mapToObj(i -> CellStyle.DEFAULT.withBackground(Color.hsb(240 - i * 7.5, 0.55, 1)))
            .toList();

    private AnimationTimer animation;

    public HeatMapExample() {
        super("Computed values", "ComputedGridModel calls a function for each visible cell. Here the function depends "
                + "on time, and refresh() repaints whatever is visible, 60 times a second. Row and column names are "
                + "functions too. Colors come from a CellStyleProvider that picks one of 32 shared styles.");
    }

    @Override
    public Node create() {
        double[] time = {0};
        ComputedGridModel wave = new ComputedGridModel(SIZE, SIZE, (row, column) ->
                Math.round(Math.sin(row / 7.0 + time[0]) * Math.cos(column / 9.0 - time[0]) * 100) / 100.0)
                .rowNames(row -> "y " + row)
                .columnNames(column -> "x " + column);

        Grid grid = new Grid(wave);
        grid.setRowHeaderVisible(true);
        grid.setCellStyleProvider((row, column, value) ->
                HEAT.get((int) Math.round(((Double) value + 1) / 2 * (HEAT.size() - 1))));

        animation = new AnimationTimer() {
            @Override
            public void handle(long now) {
                time[0] = now / 1e9;
                wave.refresh();
            }
        };
        ToggleButton run = new ToggleButton("Animate");
        run.selectedProperty().addListener((o, was, on) -> {
            if (on) {
                animation.start();
            } else {
                animation.stop();
            }
        });
        run.setSelected(true);
        return new BorderPane(grid, new ToolBar(run), null, null, null);
    }

    @Override
    public void stop() {
        if (animation != null) {
            animation.stop();
        }
    }
}
