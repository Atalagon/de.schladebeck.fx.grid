package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.PagedGridModel.PageRequest;
import de.schladebeck.fx.grid.model.PagedGridModel.SortColumn;
import de.schladebeck.fx.grid.model.PagedGridModel;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;

public final class PagedExample extends Example {

    private static final int ROWS = 200_000;

    private Timeline status;

    public PagedExample() {
        super("Paged data", "PagedGridModel loads pages of 100 rows in the background when they come into view, here "
                + "from a simulated database that answers slowly. Cells are empty until their page arrives. Click a "
                + "header, Shift+click another one to sort by both: the database sorts, the grid never loads all rows. "
                + "It keeps the 64 pages used last, more while the grid shows more at once.");
    }

    @Override
    public Node create() {
        Database database = new Database(SampleData.people(ROWS));
        PagedGridModel<Person> people = PagedGridModel.ofRecords(Person.class, ROWS, database::query);
        Grid grid = new Grid(people);
        grid.getStyleClass().add("modern"); // styles.css
        grid.getColumn("Id").setPrototypeValue(ROWS); // fitting sees the first page, IDs further down are longer

        Slider latency = new Slider(0, 1_000, database.latency);
        latency.valueProperty().addListener((o, old, ms) -> database.latency = ms.intValue());
        Button reload = new Button("Reload");
        reload.setOnAction(e -> people.reload());
        Label counters = new Label();
        status = new Timeline(new KeyFrame(Duration.millis(200), e -> counters.setText(String.format(
                "%d pages in memory, %d loading or waiting, %,d queries",
                people.getLoadedPageCount(), people.getPendingPageCount(), database.queries.get()))));
        status.setCycleCount(Animation.INDEFINITE);
        status.play();

        ToolBar tools = new ToolBar(new Label("Database latency"), latency, reload, counters);
        return new BorderPane(grid, tools, null, null, null);
    }

    @Override
    public void stop() {
        if (status != null) {
            status.stop();
        }
    }

    /** Stands in for a database table: slow to answer, sorts on its side. Called on background threads. */
    private static final class Database {

        /** The sort order per column name, like the columns of an `ORDER BY` clause. */
        private static final Map<String, Comparator<Person>> ORDER_BY = Map.of(
                "Id", Comparator.comparingInt(Person::id),
                "Name", Comparator.comparing(Person::name),
                "City", Comparator.comparing(Person::city),
                "Department", Comparator.comparing(Person::department),
                "Age", Comparator.comparingInt(Person::age),
                "Salary", Comparator.comparingDouble(Person::salary),
                "Active", Comparator.comparing(Person::active));

        private final List<Person> table;
        private final AtomicInteger queries = new AtomicInteger();
        private volatile int latency = 300;
        private List<Person> sorted;
        private List<SortColumn> sortedFor;

        Database(List<Person> table) {
            this.table = table;
        }

        /** `SELECT * FROM people ORDER BY ... LIMIT count OFFSET offset` */
        List<Person> query(PageRequest request) throws InterruptedException {
            queries.incrementAndGet();
            Thread.sleep(latency);
            List<Person> rows = sortedBy(request);
            int end = Math.min(rows.size(), request.offset() + request.count());
            return List.copyOf(rows.subList(request.offset(), end));
        }

        private synchronized List<Person> sortedBy(PageRequest request) {
            if (request.sortColumns().isEmpty()) {
                return table;
            }
            if (!request.sortColumns().equals(sortedFor)) {
                Comparator<Person> order = null;
                for (SortColumn column : request.sortColumns()) {
                    Comparator<Person> next = ORDER_BY.get(column.name());
                    next = column.ascending() ? next : next.reversed();
                    order = order == null ? next : order.thenComparing(next);
                }
                sorted = table.stream().sorted(order).toList();
                sortedFor = request.sortColumns();
            }
            return sorted;
        }
    }
}
