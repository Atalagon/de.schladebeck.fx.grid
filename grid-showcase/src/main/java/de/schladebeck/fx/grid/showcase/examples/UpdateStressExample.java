package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.AbstractGridModel;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.CellStyle;
import java.util.concurrent.ThreadLocalRandom;
import javafx.animation.AnimationTimer;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;

public final class UpdateStressExample extends Example {

    private static final int ROWS = 10_000;
    private static final int COLUMNS = 40;
    /** Updates hit these first rows, so they are on screen unless you scroll down. */
    private static final int HOT_ROWS = 60;

    private AnimationTimer timer;

    public UpdateStressExample() {
        super("Update stress test", "Changes random cells of the first 60 rows every frame and fires one event per "
                + "cell. However many events arrive, the grid repaints at most once per frame; scroll down and the "
                + "updates cost almost nothing because none of them is on screen. The label shows what is achieved; "
                + "above the screen's 60 frames per second only with vsync off (View menu).");
    }

    @Override
    public Node create() {
        Ticks ticks = new Ticks();
        Grid grid = new Grid(ticks);
        grid.setSortable(false);
        grid.setDefaultRenderer(Double.class, CellRenderers.number("0.000"));
        CellStyle up = CellStyle.DEFAULT.withBackground(Color.web("#dafbe1"));
        CellStyle down = CellStyle.DEFAULT.withBackground(Color.web("#ffebe9"));
        grid.setCellStyleProvider((row, column, value) -> (Double) value >= 0.5 ? up : down);

        ChoiceBox<Integer> perFrame =
                new ChoiceBox<>(FXCollections.observableArrayList(0, 100, 1_000, 10_000, 100_000));
        perFrame.setValue(1_000);
        Label achieved = new Label();
        timer = new AnimationTimer() {
            private long start;
            private long frames;
            private long updates;

            @Override
            public void handle(long now) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int count = perFrame.getValue();
                for (int i = 0; i < count; i++) {
                    ticks.change(random.nextInt(HOT_ROWS), random.nextInt(COLUMNS), random.nextDouble());
                }
                updates += count;
                if (start == 0 || ++frames == 60) {
                    double seconds = (now - start) / 1e9;
                    if (start != 0) {
                        achieved.setText(String.format("%.0f frames/s, %,.0f updates/s", frames / seconds,
                                updates / seconds));
                    }
                    start = now;
                    frames = 0;
                    updates = 0;
                }
            }
        };
        timer.start();
        return new BorderPane(grid, new ToolBar(new Label("Updates per frame"), perFrame, achieved), null, null, null);
    }

    @Override
    public void stop() {
        if (timer != null) {
            timer.stop();
        }
    }

    /** Numbers between 0 and 1 that change all the time. */
    private static final class Ticks extends AbstractGridModel {

        private final double[] values = new double[ROWS * COLUMNS];

        void change(int row, int column, double value) {
            values[row * COLUMNS + column] = value;
            fireRowsUpdated(row, row);
        }

        @Override
        public int getRowCount() {
            return ROWS;
        }

        @Override
        public int getColumnCount() {
            return COLUMNS;
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return Double.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return values[row * COLUMNS + column];
        }
    }
}
