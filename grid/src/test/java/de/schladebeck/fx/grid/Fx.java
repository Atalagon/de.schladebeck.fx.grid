package de.schladebeck.fx.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.schladebeck.fx.grid.model.AbstractGridModel;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/// Test support: starts the toolkit, runs code on the FX thread, lays out grids in a window-less scene, and
/// sends key, mouse and wheel input the way the user would.
///
/// Modifiers are given as words: "shift", "alt" and "shortcut" (Ctrl, or Cmd on macOS).
public final class Fx {

    private static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");

    private Fx() {
    }

    public static void start() {
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException alreadyRunning) {
            // started by another test class
        }
        // A test closing the last window would otherwise end the toolkit for all tests after it.
        Platform.setImplicitExit(false);
    }

    /** Runs {@code action} on the FX thread; a failed assertion in it fails the test as itself. */
    public static <T> T call(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    public static void run(Runnable action) throws Exception {
        call(() -> {
            action.run();
            return null;
        });
    }

    /** Puts the grid at 800x600 into a scene and runs CSS and layout, which creates the skin and paints. */
    public static Grid show(Grid grid) {
        grid.setPrefSize(800, 600);
        Pane root = new Pane(grid);
        new Scene(root);
        root.applyCss();
        root.layout();
        return grid;
    }

    /** Shows the grid in a real window with the keyboard focus, for tests that need focus. Close the stage after. */
    public static Stage window(Grid grid) {
        Stage stage = new Stage();
        stage.setScene(new Scene(new StackPane(grid), 800, 600));
        stage.show();
        grid.requestFocus();
        layout(grid);
        return stage;
    }

    /** Checks that {@code cell} is the view cell at {@code row}, {@code column}. */
    public static void assertCell(int row, int column, GridCell cell) {
        assertNotNull(cell, "no cell at " + row + ", " + column);
        assertEquals(row + ", " + column, cell.viewRow() + ", " + cell.viewColumn());
    }

    /** Runs the layout pass a pulse would run. */
    public static void layout(Grid grid) {
        grid.getParent().layout();
    }

    /** Center of a view cell, in the grid's coordinates. */
    public static Point2D center(Grid grid, int row, int column) {
        Bounds b = grid.getCellBounds(row, column);
        return new Point2D(b.getCenterX(), b.getCenterY());
    }

    /** Presses a key with the grid focused. */
    public static void key(Grid grid, KeyCode code, String... modifiers) {
        Set<String> m = Set.of(modifiers);
        boolean shortcut = m.contains("shortcut");
        Event.fireEvent(grid, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, m.contains("shift"),
                shortcut && !MAC, m.contains("alt"), shortcut && MAC));
    }

    /** Types a character with the grid focused. */
    public static void type(Grid grid, String character) {
        Event.fireEvent(grid, new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false,
                false, false));
    }

    /** One mouse event at a point in the grid's coordinates, delivered to the canvas like a real one. */
    public static void mouse(Grid grid, EventType<MouseEvent> type, double x, double y, int clicks,
                             String... modifiers) {
        Node canvas = grid.getChildrenUnmodifiable().getFirst();
        Point2D scene = grid.localToScene(x, y);
        Set<String> m = Set.of(modifiers);
        boolean shortcut = m.contains("shortcut");
        Event.fireEvent(canvas, new MouseEvent(type, scene.getX(), scene.getY(), scene.getX(), scene.getY(),
                MouseButton.PRIMARY, clicks, m.contains("shift"), shortcut && !MAC, m.contains("alt"),
                shortcut && MAC, type != MouseEvent.MOUSE_RELEASED, false, false, false, false, true, null));
    }

    /** Presses and releases the mouse at a point in the grid's coordinates. */
    public static void click(Grid grid, double x, double y, int clicks, String... modifiers) {
        mouse(grid, MouseEvent.MOUSE_PRESSED, x, y, clicks, modifiers);
        mouse(grid, MouseEvent.MOUSE_RELEASED, x, y, clicks, modifiers);
    }

    /** Presses at one point, drags to another and releases there, in the grid's coordinates. */
    public static void drag(Grid grid, double fromX, double fromY, double toX, double toY) {
        mouse(grid, MouseEvent.MOUSE_PRESSED, fromX, fromY, 1);
        mouse(grid, MouseEvent.MOUSE_DRAGGED, (fromX + toX) / 2, (fromY + toY) / 2, 1);
        mouse(grid, MouseEvent.MOUSE_DRAGGED, toX, toY, 1);
        mouse(grid, MouseEvent.MOUSE_RELEASED, toX, toY, 1);
    }

    /** Turns the mouse wheel at a point in the grid's coordinates; one notch is 40. */
    public static void wheel(Grid grid, double x, double y, double deltaY, String... modifiers) {
        Point2D scene = grid.localToScene(x, y);
        Set<String> m = Set.of(modifiers);
        boolean shortcut = m.contains("shortcut");
        Event.fireEvent(grid, new ScrollEvent(ScrollEvent.SCROLL, scene.getX(), scene.getY(), scene.getX(),
                scene.getY(), m.contains("shift"), shortcut && !MAC, false, shortcut && MAC, false, false, 0, deltaY,
                0, deltaY, ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.NONE, 0,
                0, null));
    }

    static int countNodes(Node node) {
        int count = 1;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                count += countNodes(child);
            }
        }
        return count;
    }

    /** {@code rows x columns} computed values: row * 31 + column. */
    static final class SyntheticModel extends AbstractGridModel {
        private final int rows;
        private final int columns;

        SyntheticModel(int rows, int columns) {
            this.rows = rows;
            this.columns = columns;
        }

        void update(int row, int column) {
            fireRowsUpdated(row, row);
        }

        @Override
        public int getRowCount() {
            return rows;
        }

        @Override
        public int getColumnCount() {
            return columns;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return row * 31 + column;
        }
    }

    /** Values from an array, rows can be swapped out. */
    public static final class ArrayModel extends AbstractGridModel {
        public Object[][] rows;

        public ArrayModel(Object[][] rows) {
            this.rows = rows;
        }

        @Override
        public int getRowCount() {
            return rows.length;
        }

        @Override
        public int getColumnCount() {
            return rows.length == 0 ? 0 : rows[0].length;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return rows[row][column];
        }
    }
}
