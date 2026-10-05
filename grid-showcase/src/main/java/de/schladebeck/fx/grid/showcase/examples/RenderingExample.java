package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.CellStyle;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import javafx.geometry.HPos;
import javafx.scene.Node;
import javafx.scene.paint.Color;

public final class RenderingExample extends Example {

    public enum Status { OPEN, IN_PROGRESS, BLOCKED, DONE }

    public record Task(String name, String owner, double progress, Status status, LocalDate due, double budget) {
    }

    private static final CellStyle OVERDUE = CellStyle.DEFAULT.withBackground(Color.web("#ffebe9"));

    public RenderingExample() {
        super("Renderers", "A renderer paints one cell straight onto the canvas; the progress bar here is a few "
                + "lines of its own. CellRenderers has ready-made ones: a label for the status, a bar behind the "
                + "budget, formats for dates, and progress bars and sparklines. A CellStyleProvider colors overdue "
                + "dates; it runs for every visible cell, so it returns shared CellStyle constants.");
    }

    @Override
    public Node create() {
        ListGridModel<Task> tasks = ListGridModel.ofRecords(Task.class, sampleTasks(5_000));
        Grid grid = new Grid(tasks);
        grid.getStyleClass().add("modern"); // styles.css
        grid.getColumn("Name").setWidth(220);

        grid.getColumn("Progress").setWidth(140).setHeaderStyle(CellStyle.DEFAULT.withAlignment(HPos.LEFT));
        grid.getColumn("Progress").setRenderer((gc, cell) -> {
            if (!(cell.getValue() instanceof Double progress)) {
                return;
            }
            double height = Math.round(cell.getFont().getSize() * 0.6); // grows with the font and the zoom
            double x = cell.getX() + cell.getPadding();
            double y = cell.getY() + (cell.getHeight() - height) / 2;
            double width = cell.getWidth() - 2 * cell.getPadding();
            gc.setFill(Color.web("#eaeef2"));
            gc.fillRoundRect(x, y, width, height, height, height);
            gc.setFill(progress < 0.3 ? Color.web("#cf222e")
                    : progress < 0.8 ? Color.web("#bf8700") : Color.web("#1a7f37"));
            gc.fillRoundRect(x, y, width * progress, height, height, height);
        });
        grid.getColumn("Status").setRenderer(CellRenderers.badge(
                (Status status) -> status.toString().replace('_', ' ').toLowerCase(Locale.ROOT),
                (Status status) -> switch (status) {
                    case OPEN -> Color.web("#e2e8f0");
                    case IN_PROGRESS -> Color.web("#bfdbfe");
                    case BLOCKED -> Color.web("#dc2626");
                    case DONE -> Color.web("#15803d");
                }));
        grid.getColumn("Due").setRenderer(
                CellRenderers.format(DateTimeFormatter.ofPattern("dd.MM.yyyy").toFormat(), HPos.RIGHT));
        grid.getColumn("Budget").setRenderer(CellRenderers.dataBar(0, 100_000, Color.web("#6366f1")));

        LocalDate today = LocalDate.now();
        grid.setCellStyleProvider((row, column, value) -> value instanceof LocalDate due
                && tasks.getItem(row).status() != Status.DONE && today.isAfter(due) ? OVERDUE : null);
        return grid;
    }

    private static List<Task> sampleTasks(int count) {
        String[] verbs = {"Migrate", "Review", "Design", "Test", "Document", "Deploy", "Refactor", "Plan"};
        String[] things = {"billing service", "login flow", "search index", "release notes", "data export",
                "mobile layout", "audit log", "price engine"};
        String[] owners = {"Anna", "Ben", "Clara", "David", "Eva", "Felix"};
        Random random = new Random(3);
        List<Task> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Status status = Status.values()[random.nextInt(4)];
            double progress = status == Status.DONE ? 1 : random.nextDouble() * 0.95;
            tasks.add(new Task(verbs[random.nextInt(verbs.length)] + " " + things[random.nextInt(things.length)],
                    owners[random.nextInt(owners.length)], progress, status,
                    LocalDate.now().plusDays(random.nextInt(60) - 20), 1_000 + random.nextInt(99_000)));
        }
        return tasks;
    }
}
