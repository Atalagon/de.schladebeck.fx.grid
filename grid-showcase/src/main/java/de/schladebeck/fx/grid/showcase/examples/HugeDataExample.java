package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ComputedGridModel;
import java.util.Random;
import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;

/// 100 million rows or 100 million columns, both shown by the same code.
public final class HugeDataExample extends Example {

    private static final int HUGE = 100_000_000;
    private static final int LINES_PER_FRAME = 50;

    private final boolean wide;
    private AnimationTimer autoScroll;

    private HugeDataExample(boolean wide) {
        super(wide ? "100 million columns" : "100 million rows",
                (wide ? "1,000 rows x 100,000,000 columns. " : "100,000,000 rows x 1,000 columns. ")
                        + "A ComputedGridModel computes its values, so the cells need no memory. Only visible cells "
                        + "are painted, and the grid keeps only columns and rows that differ from the default, so "
                        + "scrolling costs the same as for a small table. Sorting is off here, it would have to read "
                        + "every value. Turn vsync off in the View menu to see frame rates above the screen's.");
        this.wide = wide;
    }

    /** 100,000,000 rows x 1,000 columns. */
    public static HugeDataExample rows() {
        return new HugeDataExample(false);
    }

    /** 1,000 rows x 100,000,000 columns. */
    public static HugeDataExample columns() {
        return new HugeDataExample(true);
    }

    @Override
    public Node create() {
        int rows = wide ? 1_000 : HUGE;
        int columns = wide ? HUGE : 1_000;
        ComputedGridModel model = new ComputedGridModel(rows, columns, (row, column) -> {
            if (column == 0) {
                return row + 1;
            }
            return Math.floorMod(row * 31 + column * 17, 100_000) / 100.0;
        }).columnNames(column -> column == 0 ? "ID" : "Value " + column);

        Grid grid = new Grid(model);
        grid.setFrozenColumnCount(1);
        grid.getColumn("ID").setPrototypeValue(rows); // fitting looks at the first rows, the last ID is the longest
        grid.setSortable(false);

        Label speed = new Label();
        ToggleButton scroll = new ToggleButton("Auto-scroll");
        scroll.selectedProperty().addListener((o, was, on) -> {
            if (on) {
                autoScroll = scroller(grid, speed);
                autoScroll.start();
            } else {
                stop();
            }
        });
        Random random = new Random();
        Button jump = new Button(wide ? "Jump to a random column" : "Jump to a random row");
        jump.setOnAction(e -> {
            int row = wide ? 3 : random.nextInt(rows);
            int column = wide ? random.nextInt(columns) : 3;
            grid.getSelectionModel().select(row, column);
            grid.scrollTo(row, column);
            grid.requestFocus();
        });
        return new BorderPane(grid, new ToolBar(scroll, jump, speed), null, null, null);
    }

    @Override
    public void stop() {
        if (autoScroll != null) {
            autoScroll.stop();
        }
    }

    /** Scrolls 50 rows or columns per frame, and a little in the other direction, and shows the frame rate. */
    private AnimationTimer scroller(Grid grid, Label speed) {
        return new AnimationTimer() {
            private long start;
            private long frames;

            @Override
            public void handle(long now) {
                if (wide) {
                    grid.setScrollX(grid.getScrollX() + LINES_PER_FRAME * grid.getColumn(0).getWidth());
                    grid.setScrollY(grid.getScrollY() + 1);
                } else {
                    grid.setScrollY(grid.getScrollY() + LINES_PER_FRAME * grid.getRowHeight());
                    grid.setScrollX(grid.getScrollX() + 2);
                }
                if (start == 0) {
                    start = now;
                } else if (++frames % 30 == 0) {
                    double fps = frames / ((now - start) / 1e9);
                    speed.setText(String.format("%.0f frames/s, %,.0f %s/s", fps, fps * LINES_PER_FRAME,
                            wide ? "columns" : "rows"));
                }
            }
        };
    }
}
