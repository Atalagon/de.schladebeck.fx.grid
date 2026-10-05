package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.TreeGrid;
import de.schladebeck.fx.grid.model.TreeGridModel;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRenderers;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import javafx.geometry.HPos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;

public final class FileSystemExample extends Example {

    /// A file or a folder. A folder's entries are made from its seed when it is opened for the first time, so the
    /// file system can be as large as wanted.
    public record Entry(String name, String type, Long size, LocalDateTime modified, long seed, int depth) {

        boolean folder() {
            return size == null;
        }
    }

    private static final String[] FOLDERS = {"src", "docs", "assets", "archive", "backup", "reports", "photos",
            "2025", "2026", "drafts", "shared", "build", "data", "music", "invoices", "travel"};
    private static final String[] WORDS = {"summary", "notes", "budget", "plan", "holiday", "logo", "invoice",
            "readme", "contract", "slides", "export", "track", "screenshot", "letter", "report", "index"};
    private static final String[][] TYPES = {{"txt", "Text"}, {"png", "PNG image"}, {"pdf", "PDF document"},
            {"xlsx", "Spreadsheet"}, {"java", "Java source"}, {"mp3", "Audio"}, {"zip", "Archive"},
            {"docx", "Document"}};

    /** Bytes as KB, MB or GB. */
    private static final CellRenderer SIZE = (gc, cell) -> {
        if (cell.getValue() instanceof Long bytes) {
            String[] units = {"bytes", "KB", "MB", "GB"};
            double value = bytes;
            int unit = 0;
            while (value >= 1024 && unit < units.length - 1) {
                value /= 1024;
                unit++;
            }
            String text = unit == 0 ? bytes + " bytes" : String.format("%.1f %s", value, units[unit]);
            CellRenderers.drawText(gc, cell, text, HPos.RIGHT);
        }
    };

    public FileSystemExample() {
        super("File system", "A made-up file system of any depth. The children function makes a folder's entries only "
                + "when it is opened, and hasChildren tells which rows get a triangle without making their children. "
                + "Opening a folder widens the name column when its entries need more room. Sorting sorts the entries "
                + "of each folder.");
    }

    @Override
    public Node create() {
        AtomicLong made = new AtomicLong();
        Label count = new Label();
        TreeGridModel<Entry> files = new TreeGridModel<Entry>(entry -> {
            List<Entry> entries = entries(entry);
            count.setText(String.format("%,d entries made so far", made.addAndGet(entries.size())));
            return entries;
        })
                .hasChildren(Entry::folder)
                .column("Name", Entry::name)
                .column("Type", Entry::type)
                .column("Size", Entry::size)
                .column("Modified", Entry::modified);
        files.setRoots(List.of(
                folder("Documents", 1), folder("Pictures", 2), folder("Music", 3), folder("Projects", 4),
                folder("Downloads", 5)));

        TreeGrid grid = new TreeGrid(files);
        grid.getStyleClass().add("modern"); // styles.css
        grid.getColumn("Name").setRenderer(name(files));
        grid.getColumn("Size").setRenderer(SIZE).setPrototypeValue(999_999_999L);
        grid.getColumn("Modified").setRenderer(
                CellRenderers.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").toFormat()));
        files.setExpanded(3, true);
        return new BorderPane(grid, new ToolBar(count), null, null, null);
    }

    /** A folder or page symbol in front of the name. */
    private static CellRenderer name(TreeGridModel<Entry> files) {
        return (gc, cell) -> {
            if (!(cell.getValue() instanceof String name)) {
                return;
            }
            double size = Math.round(cell.getFont().getSize() * 1.2);
            double x = cell.getX() + cell.getPadding();
            double y = cell.getY() + (cell.getHeight() - size) / 2;
            if (cell.getModelRow() >= 0 && !files.getItem(cell.getModelRow()).folder()) {
                gc.setFill(Color.web("#cbd5e1"));
                gc.fillRoundRect(x + size * 0.15, y, size * 0.7, size, 3, 3);
                gc.setFill(Color.web("#64748b"));
                gc.fillRect(x + size * 0.3, y + size * 0.35, size * 0.4, 1);
                gc.fillRect(x + size * 0.3, y + size * 0.55, size * 0.4, 1);
            } else {
                gc.setFill(Color.web("#f5b942"));
                gc.fillRoundRect(x, y + size * 0.1, size * 0.45, size * 0.3, 3, 3);
                gc.fillRoundRect(x, y + size * 0.2, size, size * 0.7, 3, 3);
            }
            cell.indent(size + cell.getFont().getSize() * 0.5);
            CellRenderers.drawText(gc, cell, name, HPos.LEFT);
        };
    }

    private static Entry folder(String name, long seed) {
        return new Entry(name, "Folder", null, LocalDateTime.of(2026, 1, 1, 9, 0), seed, 0);
    }

    /** The entries of a folder, the same every time for the same folder: some folders, then files. */
    private static List<Entry> entries(Entry folder) {
        Random random = new Random(folder.seed());
        List<Entry> entries = new ArrayList<>();
        int folders = folder.depth() < 6 ? 2 + random.nextInt(6) : 0;
        for (int i = 0; i < folders; i++) {
            entries.add(new Entry(FOLDERS[random.nextInt(FOLDERS.length)] + (i == 0 ? "" : "-" + i), "Folder",
                    null, modified(random), random.nextLong(), folder.depth() + 1));
        }
        int count = 3 + random.nextInt(30);
        for (int i = 0; i < count; i++) {
            String[] type = TYPES[random.nextInt(TYPES.length)];
            entries.add(new Entry(WORDS[random.nextInt(WORDS.length)] + "-" + (i + 1) + "." + type[0], type[1],
                    (long) Math.pow(10, 2 + random.nextDouble() * 7), modified(random), 0, folder.depth() + 1));
        }
        return entries;
    }

    private static LocalDateTime modified(Random random) {
        return LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(random.nextInt(60 * 24 * 270));
    }
}
