package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.TreeGrid;
import de.schladebeck.fx.grid.model.TreeGridModel;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.CellStyle;
import de.schladebeck.fx.grid.render.TextMetrics;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import javafx.geometry.HPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

public final class ProjectPlanExample extends Example {

    public enum Status {
        PLANNED("Planned", "#475569", "#f1f5f9"),
        IN_PROGRESS("In progress", "#1d4ed8", "#dbeafe"),
        IN_REVIEW("In review", "#7e22ce", "#f3e8ff"),
        BLOCKED("Blocked", "#b91c1c", "#fee2e2"),
        DONE("Done", "#15803d", "#dcfce7");

        final String label;
        final Color text;
        final Color background;

        Status(String label, String text, String background) {
            this.label = label;
            this.text = Color.web(text);
            this.background = Color.web(background);
        }
    }

    /** A phase, task or subtask; one with subtasks takes its status, progress and dates from them. */
    public record Task(String name, String owner, Status status, double progress, LocalDate start, LocalDate due,
                       List<Task> subtasks) {
    }

    private static final Color[] AVATARS = {Color.web("#6366f1"), Color.web("#0ea5e9"), Color.web("#14b8a6"),
            Color.web("#f59e0b"), Color.web("#ec4899"), Color.web("#8b5cf6"), Color.web("#ef4444")};
    private static final CellStyle PHASE = CellStyle.DEFAULT.withFontWeight(FontWeight.BOLD);
    private static final CellStyle OVERDUE = CellStyle.DEFAULT.withTextFill(Color.web("#dc2626"));

    /** A round avatar with the owner's initials, then the name. */
    private static final CellRenderer OWNER = (gc, cell) -> {
        if (!(cell.getValue() instanceof String name)) {
            return;
        }
        Font font = cell.getFont();
        double size = Math.round(font.getSize() * 1.8);
        double x = cell.getX() + cell.getPadding();
        double y = cell.getY() + (cell.getHeight() - size) / 2;
        gc.setFill(AVATARS[Math.floorMod(name.hashCode(), AVATARS.length)]);
        gc.fillOval(x, y, size, size);
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font(font.getFamily(), FontWeight.BOLD, font.getSize() * 0.75));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(initials(name), x + size / 2, y + size / 2);
        cell.indent(size + font.getSize() * 0.6);
        CellRenderers.drawText(gc, cell, name, HPos.LEFT);
    };

    /** The status as a colored label with round ends. */
    private static final CellRenderer STATUS = (gc, cell) -> {
        if (!(cell.getValue() instanceof Status status)) {
            return;
        }
        Font font = Font.font(cell.getFont().getFamily(), FontWeight.BOLD, cell.getFont().getSize() * 0.85);
        double height = Math.round(font.getSize() * 1.9);
        double width = TextMetrics.width(status.label, font) + height;
        double x = cell.getX() + cell.getPadding();
        double y = cell.getY() + (cell.getHeight() - height) / 2;
        gc.setFill(status.background);
        gc.fillRoundRect(x, y, width, height, height, height);
        gc.setFill(status.text);
        gc.setFont(font);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(status.label, x + width / 2, y + height / 2);
    };

    /** A bar with round ends and the percentage after it. */
    private static final CellRenderer PROGRESS = (gc, cell) -> {
        if (!(cell.getValue() instanceof Double progress)) {
            return;
        }
        Font font = cell.getFont();
        double room = TextMetrics.width("100 %", font) + font.getSize();
        double height = Math.round(font.getSize() * 0.5);
        double x = cell.getX() + cell.getPadding();
        double y = cell.getY() + (cell.getHeight() - height) / 2;
        double width = Math.max(0, cell.getWidth() - 2 * cell.getPadding() - room);
        gc.setFill(Color.web("#e2e8f0"));
        gc.fillRoundRect(x, y, width, height, height, height);
        gc.setFill(progress >= 1 ? Status.DONE.text : Color.web("#6366f1"));
        gc.fillRoundRect(x, y, width * progress, height, height, height);
        CellRenderers.drawText(gc, cell, Math.round(progress * 100) + " %", HPos.RIGHT);
    };

    public ProjectPlanExample() {
        super("Project plan", "A tree of phases, tasks and subtasks, with the light look from styles.css. The owner, "
                + "status and progress columns have renderers that paint an avatar, a colored label and a bar; a phase "
                + "is bold and an overdue date red, from a CellStyleProvider. Sorting sorts the tasks within each "
                + "phase.");
    }

    @Override
    public Node create() {
        TreeGridModel<Task> plan = new TreeGridModel<Task>(Task::subtasks)
                .column("Task", Task::name)
                .column("Owner", Task::owner)
                .column("Status", Task::status)
                .column("Progress", Task::progress)
                .column("Start", Task::start)
                .column("Due", Task::due);
        plan.setRoots(phases(new Random(11)));

        TreeGrid grid = new TreeGrid(plan);
        grid.getStyleClass().add("modern"); // styles.css
        grid.getColumn("Task").setWidth(320);
        grid.getColumn("Owner").setRenderer(OWNER).setWidth(190);
        grid.getColumn("Status").setRenderer(STATUS).setWidth(130);
        grid.getColumn("Progress").setRenderer(PROGRESS).setWidth(170)
                .setHeaderStyle(CellStyle.DEFAULT.withAlignment(HPos.LEFT)); // where the bar starts
        DateTimeFormatter date = DateTimeFormatter.ofPattern("d MMM yyyy");
        grid.getColumn("Start").setRenderer(CellRenderers.format(date.toFormat())).setWidth(120);
        grid.getColumn("Due").setRenderer(CellRenderers.format(date.toFormat())).setWidth(120);
        LocalDate today = LocalDate.of(2026, 6, 15); // the plan's today, so the example looks the same any day
        int dueColumn = plan.findColumn("Due");
        grid.setCellStyleProvider((row, column, value) -> {
            Task task = plan.getItem(row);
            if (column == 0 && plan.getDepth(row) == 0) { // the tree column
                return PHASE;
            }
            return column == dueColumn && task.status() != Status.DONE && task.due().isBefore(today) ? OVERDUE
                    : null;
        });
        for (int row = 0; row < plan.getRowCount(); row++) {
            plan.setExpanded(row, plan.getDepth(row) == 0);
        }

        Button expand = new Button("Expand all");
        expand.setOnAction(e -> {
            for (int row = 0; row < plan.getRowCount(); row++) { // opening a row adds the rows after it
                plan.setExpanded(row, true);
            }
        });
        Button collapse = new Button("Collapse all");
        collapse.setOnAction(e -> {
            for (int row = plan.getRowCount() - 1; row >= 0; row--) {
                plan.setExpanded(row, false);
            }
        });
        return new BorderPane(grid, new ToolBar(expand, collapse), null, null, null);
    }

    private static String initials(String name) {
        String[] parts = name.split(" ");
        return parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1);
    }

    private static List<Task> phases(Random random) {
        String[][] tasks = {
                {"Discovery", "Interview users", "Review the competition", "Write the brief"},
                {"Design", "Information architecture", "Wireframes", "Visual design", "Prototype test"},
                {"Build", "Data model", "REST API", "Grid views", "Reports", "Notifications", "Single sign-on"},
                {"Launch", "Load test", "Documentation", "Training", "Go live"}};
        LocalDate start = LocalDate.of(2026, 3, 2);
        List<Task> phases = new ArrayList<>();
        for (String[] phase : tasks) {
            List<Task> children = new ArrayList<>();
            for (int i = 1; i < phase.length; i++) {
                children.add(task(phase[i], start.plusDays(i * 6L), random, true));
            }
            phases.add(summary(phase[0], SampleData.name(random), children));
            start = start.plusDays(phase.length * 7L);
        }
        return phases;
    }

    /** A task, and for some of them subtasks. */
    private static Task task(String name, LocalDate start, Random random, boolean withSubtasks) {
        int subtasks = withSubtasks ? random.nextInt(4) : 0;
        if (subtasks == 0) {
            Status status = Status.values()[random.nextInt(Status.values().length)];
            double progress = switch (status) {
                case PLANNED -> 0;
                case DONE -> 1;
                case IN_REVIEW -> 0.9;
                default -> Math.round(random.nextDouble() * 8 + 1) / 10.0;
            };
            return new Task(name, SampleData.name(random), status, progress, start,
                    start.plusDays(3 + random.nextInt(12)), List.of());
        }
        List<Task> children = new ArrayList<>();
        for (int i = 1; i <= subtasks; i++) {
            children.add(task(name + ", part " + i, start.plusDays(i * 2L), random, false));
        }
        return summary(name, SampleData.name(random), children);
    }

    /** A task made of others: blocked if one is, done if all are, otherwise as far as the average. */
    private static Task summary(String name, String owner, List<Task> children) {
        double progress = children.stream().mapToDouble(Task::progress).average().orElse(0);
        Status status = children.stream().anyMatch(t -> t.status() == Status.BLOCKED) ? Status.BLOCKED
                : progress >= 1 ? Status.DONE : progress > 0 ? Status.IN_PROGRESS : Status.PLANNED;
        return new Task(name, owner, status, progress,
                children.stream().map(Task::start).min(Comparator.naturalOrder()).orElseThrow(),
                children.stream().map(Task::due).max(Comparator.naturalOrder()).orElseThrow(), children);
    }
}
