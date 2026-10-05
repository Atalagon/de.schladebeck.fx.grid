package de.schladebeck.fx.grid.showcase;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ListGridModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.stream.IntStream;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableListBase;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/// Runs the same scenarios on a [Grid] and on a JavaFX `TableView` showing the same data in the same window, and
/// prints both results side by side.
///
/// Both read every cell from one function of row and column ([SyntheticModel]), so the data costs the same for
/// both. TableView is set up the fast way: a fixed cell size, which also skips the cells of columns out of view,
/// and its default text cells. The grid formats numbers and paints check boxes, so it does a little more per cell.
///
/// Frame rates are measured like in [ScrollBenchmark], with vsync and the 60 fps cap switched off. Operations on
/// 1,000,000 rows (showing, sorting, filtering, selecting, a live feed) are timed on the JavaFX thread including
/// the layout pass that follows, without painting. TableView sorts and filters through a `SortedList` over a
/// `FilteredList`, the usual way. Clearing a selection is timed on 80,000 rows: TableView's time for it grows with
/// the square of the selected rows and reaches minutes at 1,000,000.
public final class TableViewComparison {

    private static final double WARMUP_SECONDS = 1;
    private static final double MEASURE_SECONDS = 2.5;
    private static final double WIDTH = 1280;
    private static final double HEIGHT = 800;
    private static final double ROW_HEIGHT = 24;
    private static final double COLUMN_WIDTH = 100;
    private static final int OPERATION_ROWS = 1_000_000;
    private static final int OPERATION_COLUMNS = 20;
    private static final int CLEARED_ROWS = 80_000;
    private static final int AMOUNT = 2;
    private static final int NAME = 1;

    /** Per frame: scroll by dx, dy pixels and fire this many updates of visible rows. */
    private record Scenario(String name, double dx, double dy, int updates) {
    }

    private record Setup(String name, int rows, int columns, List<Scenario> scenarios) {
    }

    private static final List<Setup> SETUPS = List.of(
            new Setup("1,000,000 rows x 20 columns", 1_000_000, 20, List.of(
                    new Scenario("vertical scroll", 0, 7, 0),
                    new Scenario("horizontal scroll", 7, 0, 0),
                    new Scenario("1,000 rows per frame", 0, 24_000, 0),
                    new Scenario("1,000 row updates per frame", 0, 0, 1_000))),
            new Setup("100,000,000 rows x 20 columns", 100_000_000, 20, List.of(
                    new Scenario("vertical scroll", 0, 7, 0),
                    new Scenario("1,000 rows per frame", 0, 24_000, 0))),
            new Setup("10,000 rows x 1,000 columns", 10_000, 1_000, List.of(
                    new Scenario("horizontal scroll", 7, 0, 0),
                    new Scenario("diagonal scroll", 4, 7, 0),
                    new Scenario("a screen width per frame", WIDTH, 0, 0))));

    private static final String[] KINDS = {"Grid", "TableView"};

    private TableViewComparison() {
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
            stage.setScene(new Scene(new StackPane(), WIDTH, HEIGHT));
            stage.setTitle("Grid and TableView");
            stage.show();
            new TableViewComparison().run(stage);
        }
    }

    /** One of the two controls, showing a setup's data. */
    private interface Subject {

        Parent node();

        void scrollTo(double x, double y);

        void touch(int row);
    }

    /** Frames per second per setup, scenario and control. */
    private final Map<String, double[]> frameRates = new LinkedHashMap<>();
    /** Milliseconds (or other units, see the name) per operation and control. */
    private final Map<String, double[]> operations = new LinkedHashMap<>();
    private final List<Runnable> steps = new ArrayList<>();

    private void run(Stage stage) {
        for (Setup setup : SETUPS) {
            for (int kind = 0; kind < KINDS.length; kind++) {
                int k = kind;
                Subject[] subject = new Subject[1];
                steps.add(() -> {
                    subject[0] = k == 0 ? grid(setup) : table(setup);
                    stage.getScene().setRoot(new StackPane(subject[0].node()));
                    next();
                });
                for (Scenario scenario : setup.scenarios()) {
                    steps.add(() -> measure(subject[0], setup, scenario, fps -> {
                        frameRates.computeIfAbsent(setup.name() + "|" + scenario.name(), key -> new double[2])[k] = fps;
                        System.out.printf("%s, %s, %s: %.0f fps%n", KINDS[k], setup.name(), scenario.name(), fps);
                        next();
                    }));
                }
            }
        }
        for (int kind = 0; kind < KINDS.length; kind++) {
            int k = kind;
            steps.add(() -> {
                operations(stage, k);
                next();
            });
        }
        steps.add(() -> {
            print();
            Platform.exit();
        });
        next();
    }

    private void next() {
        Runnable step = steps.removeFirst();
        Platform.runLater(step);
    }

    // ---- the two controls over the same data

    private static Subject grid(Setup setup) {
        SyntheticModel model = new SyntheticModel(setup.rows(), setup.columns());
        Grid grid = new Grid(model);
        for (int column = 0; column < setup.columns(); column++) {
            grid.getColumn(column).setWidth(COLUMN_WIDTH);
        }
        return new Subject() {
            @Override
            public Parent node() {
                return grid;
            }

            @Override
            public void scrollTo(double x, double y) {
                grid.setScrollX(x);
                grid.setScrollY(y);
            }

            @Override
            public void touch(int row) {
                model.touch(row);
            }
        };
    }

    private static Subject table(Setup setup) {
        RowNumbers rows = new RowNumbers(setup.rows());
        TableView<Integer> table = tableView(rows, new SyntheticModel(setup.rows(), setup.columns()), setup.columns());
        return new Subject() {
            private VirtualFlow<?> flow;
            private ScrollBar horizontal;
            private double lastY;

            @Override
            public Parent node() {
                return table;
            }

            @Override
            public void scrollTo(double x, double y) {
                if (flow == null) {
                    flow = (VirtualFlow<?>) table.lookup(".virtual-flow");
                    for (Node node : table.lookupAll(".scroll-bar")) {
                        if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.HORIZONTAL) {
                            horizontal = bar;
                        }
                    }
                }
                if (y < lastY) {
                    flow.scrollTo(0);
                    lastY = 0;
                }
                flow.scrollPixels(y - lastY);
                lastY = y;
                horizontal.setValue(Math.min(x, horizontal.getMax()));
            }

            @Override
            public void touch(int row) {
                rows.touch(row);
            }
        };
    }

    private static TableView<Integer> tableView(ObservableList<Integer> items, SyntheticModel values, int columns) {
        TableView<Integer> table = new TableView<>(items);
        table.setFixedCellSize(ROW_HEIGHT);
        for (int c = 0; c < columns; c++) {
            int column = c;
            TableColumn<Integer, Object> tableColumn = new TableColumn<>(values.getColumnName(column));
            tableColumn.setCellValueFactory(
                    cell -> new ReadOnlyObjectWrapper<>(values.getValueAt(cell.getValue(), column)));
            tableColumn.setPrefWidth(COLUMN_WIDTH);
            table.getColumns().add(tableColumn);
        }
        return table;
    }

    /** Row numbers as a list without memory, so TableView can show as many rows as the grid. */
    private static final class RowNumbers extends ObservableListBase<Integer> {

        private final int size;

        RowNumbers(int size) {
            this.size = size;
        }

        @Override
        public Integer get(int index) {
            return Objects.checkIndex(index, size);
        }

        @Override
        public int size() {
            return size;
        }

        /** Replaces a row by itself, the list's way to say that it changed. */
        void touch(int index) {
            beginChange();
            nextSet(index, index);
            endChange();
        }
    }

    // ---- frame rates

    private static void measure(Subject subject, Setup setup, Scenario scenario, DoubleConsumer result) {
        // Scrolled positions wrap around before the end, so every frame scrolls; 100 px spare the scroll bars.
        double maxX = setup.columns() * COLUMN_WIDTH - WIDTH - 100;
        double maxY = setup.rows() * ROW_HEIGHT - HEIGHT - 100;
        new AnimationTimer() {
            private long start = -1;
            private boolean measuring;
            private long frames;
            private double x;
            private double y;

            @Override
            public void handle(long now) {
                if (start < 0) {
                    start = now;
                }
                double seconds = (now - start) / 1e9;
                if (!measuring && seconds >= WARMUP_SECONDS) {
                    measuring = true;
                    start = now;
                    frames = 0;
                } else if (measuring && seconds >= MEASURE_SECONDS) {
                    stop();
                    result.accept(frames / seconds);
                    return;
                }
                x = (x + scenario.dx()) % maxX;
                y = (y + scenario.dy()) % maxY;
                subject.scrollTo(x, y);
                for (int i = 0; i < scenario.updates(); i++) {
                    subject.touch(i % 30);
                }
                frames++;
            }
        }.start();
    }

    // ---- operations on 1,000,000 rows

    /** The operations every table needs, for one control. */
    private interface Operations {

        Parent node();

        void sort(int column, boolean ascending);

        void filter(boolean on);

        void selectAll();

        void clearSelection();

        /** Appends a row and replaces one, like a live feed. */
        void feed(int newRow, int replacedRow);
    }

    private static Operations gridOperations(List<Integer> ids) {
        SyntheticModel values = new SyntheticModel(ids.size(), OPERATION_COLUMNS);
        ListGridModel<Integer> model = new ListGridModel<>();
        for (int c = 0; c < OPERATION_COLUMNS; c++) {
            int column = c;
            model.column(values.getColumnName(column), id -> values.getValueAt(id, column));
        }
        model.setAll(ids);
        Grid grid = new Grid(model);
        for (int column = 0; column < OPERATION_COLUMNS; column++) {
            grid.getColumn(column).setWidth(COLUMN_WIDTH);
        }
        return new Operations() {
            @Override
            public Parent node() {
                return grid;
            }

            @Override
            public void sort(int column, boolean ascending) {
                grid.sort(column, ascending);
            }

            @Override
            public void filter(boolean on) {
                grid.setRowFilter(on ? row -> model.getItem(row) % 3 != 0 : null);
            }

            @Override
            public void selectAll() {
                grid.getSelectionModel().selectAll();
            }

            @Override
            public void clearSelection() {
                grid.getSelectionModel().clear();
            }

            @Override
            public void feed(int newRow, int replacedRow) {
                model.add(newRow);
                model.set(replacedRow, model.getItem(replacedRow));
            }
        };
    }

    private static Operations tableOperations(List<Integer> ids) {
        ObservableList<Integer> source = FXCollections.observableArrayList(ids);
        FilteredList<Integer> filtered = new FilteredList<>(source);
        SortedList<Integer> sorted = new SortedList<>(filtered);
        TableView<Integer> table = tableView(sorted, new SyntheticModel(ids.size(), OPERATION_COLUMNS),
                OPERATION_COLUMNS);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        return new Operations() {
            @Override
            public Parent node() {
                return table;
            }

            @Override
            public void sort(int column, boolean ascending) {
                TableColumn<Integer, ?> sortColumn = table.getColumns().get(column);
                sortColumn.setSortType(ascending ? TableColumn.SortType.ASCENDING : TableColumn.SortType.DESCENDING);
                if (!table.getSortOrder().equals(List.of(sortColumn))) {
                    table.getSortOrder().setAll(List.of(sortColumn));
                }
            }

            @Override
            public void filter(boolean on) {
                filtered.setPredicate(on ? id -> id % 3 != 0 : null);
            }

            @Override
            public void selectAll() {
                table.getSelectionModel().selectAll();
            }

            @Override
            public void clearSelection() {
                table.getSelectionModel().clearSelection();
            }

            @Override
            public void feed(int newRow, int replacedRow) {
                source.add(newRow);
                source.set(replacedRow, source.get(replacedRow));
            }
        };
    }

    private void operations(Stage stage, int kind) {
        Scene scene = stage.getScene();
        List<Integer> ids = IntStream.range(0, OPERATION_ROWS).boxed().toList();
        Operations[] built = new Operations[1];

        double[] show = new double[3];
        for (int i = 0; i < show.length; i++) {
            scene.setRoot(new StackPane());
            layout(scene);
            show[i] = time(scene, () -> {
                built[0] = kind == 0 ? gridOperations(ids) : tableOperations(ids);
                scene.setRoot(new StackPane(built[0].node()));
            });
        }
        put("Show 1,000,000 rows (ms)", kind, median(show));
        put("Nodes in the control", kind, ScrollBenchmark.countNodes(built[0].node()));

        scene.setRoot(new StackPane());
        built[0] = null;
        long before = usedHeap();
        Operations control = kind == 0 ? gridOperations(ids) : tableOperations(ids);
        scene.setRoot(new StackPane(control.node()));
        layout(scene);
        control.sort(AMOUNT, true);
        control.filter(true);
        layout(scene);
        put("Heap for control, sorted and filtered view (MB)", kind, (usedHeap() - before) / 1e6);
        control.filter(false);
        layout(scene);

        put("Sort by a number (ms)", kind, repeat(scene, run -> { }, run -> control.sort(AMOUNT, run % 2 == 1)));
        put("Sort by text (ms)", kind, repeat(scene, run -> { }, run -> control.sort(NAME, run % 2 == 0)));
        control.sort(AMOUNT, true);
        layout(scene);
        put("Filter out a third of the rows (ms)", kind,
                repeat(scene, run -> control.filter(false), run -> control.filter(true)));
        control.filter(false);
        layout(scene);
        int next = OPERATION_ROWS;
        double[] frames = new double[40];
        for (int frame = -10; frame < frames.length; frame++) {
            int first = next;
            next += 5;
            double ms = time(scene, () -> {
                for (int i = 0; i < 5; i++) {
                    control.feed(first + i, i);
                }
            });
            if (frame >= 0) {
                frames[frame] = ms;
            }
        }
        put("Live feed into the sorted view, 5 new and 5 changed rows (ms per frame)", kind, median(frames));
        // Last, and only once: TableView takes minutes to clear a selection of 1,000,000 rows, see below.
        put("Select all (ms)", kind, time(scene, control::selectAll));

        // Clearing a selection costs TableView time that grows with the square of the selected rows.
        Operations small = kind == 0 ? gridOperations(ids.subList(0, CLEARED_ROWS))
                : tableOperations(ids.subList(0, CLEARED_ROWS));
        scene.setRoot(new StackPane(small.node()));
        layout(scene);
        small.selectAll();
        layout(scene);
        put(String.format("Clear a selection of all %,d rows (ms)", CLEARED_ROWS), kind,
                time(scene, small::clearSelection));
        scene.setRoot(new StackPane());
        layout(scene);
    }

    private interface Run {
        void run(int index);
    }

    /// Median time of an operation after two runs to warm up; the layout pass counts, painting does not.
    /// `prepare` runs before each, untimed.
    private static double repeat(Scene scene, Run prepare, Run operation) {
        double[] times = new double[5];
        for (int run = -2; run < times.length; run++) {
            int index = run + 2;
            prepare.run(index);
            layout(scene);
            double ms = time(scene, () -> operation.run(index));
            if (run >= 0) {
                times[run] = ms;
            }
        }
        return median(times);
    }

    private static double time(Scene scene, Runnable action) {
        long start = System.nanoTime();
        action.run();
        layout(scene);
        return (System.nanoTime() - start) / 1e6;
    }

    private static void layout(Scene scene) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private void put(String name, int kind, double value) {
        System.out.printf("%s, %s: %.1f%n", KINDS[kind], name, value);
        operations.computeIfAbsent(name, key -> new double[2])[kind] = value;
    }

    private void print() {
        System.out.printf("%nFrames per second, %.0fx%.0f window%n", WIDTH, HEIGHT);
        System.out.printf("%-60s %10s %10s%n", "", KINDS[0], KINDS[1]);
        String setup = "";
        for (Map.Entry<String, double[]> entry : frameRates.entrySet()) {
            String[] names = entry.getKey().split("\\|");
            if (!names[0].equals(setup)) {
                setup = names[0];
                System.out.println(setup);
            }
            System.out.printf("  %-58s %10.0f %10.0f%n", names[1], entry.getValue()[0], entry.getValue()[1]);
        }
        System.out.printf("%nOperations on %,d rows x %d columns%n", OPERATION_ROWS, OPERATION_COLUMNS);
        for (Map.Entry<String, double[]> entry : operations.entrySet()) {
            System.out.printf("  %-58s %10.1f %10.1f%n", entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
        }
    }
}
