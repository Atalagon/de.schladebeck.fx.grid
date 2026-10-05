package de.schladebeck.fx.grid.showcase;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.SelectionMode;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.TextMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/// The source pane: the files of the current example, one at a time, picked with the buttons above it. The code is
/// shown in a [Grid], one line per row with its number in the row header, and a renderer that colors Java and CSS.
/// Lines can be selected and copied with Ctrl+C like in any grid.
final class SourceView extends BorderPane {

    /** A file to show, by name. */
    record SourceFile(String name, String text) {
    }

    /** The kinds of text the renderer colors, with their colors. */
    private enum Kind {
        PLAIN(null), KEYWORD("#0033b3"), STRING("#067d17"), COMMENT("#8c8c8c"), NUMBER("#1750eb"),
        ANNOTATION("#9e880d");

        final Color color;

        Kind(String color) {
            this.color = color == null ? null : Color.web(color);
        }
    }

    /** A line and its colored parts: part `i` runs from `starts[i]` to `starts[i + 1]`, the last one to the end. */
    private record Line(String text, int[] starts, Kind[] kinds) {
    }

    private static final Set<String> KEYWORDS = Set.of("abstract", "boolean", "break", "byte", "case", "catch",
            "char", "class", "continue", "default", "do", "double", "else", "enum", "extends", "false", "final",
            "finally", "float", "for", "if", "implements", "import", "instanceof", "int", "interface", "long", "new",
            "null", "private", "protected", "public", "record", "return", "short", "static", "super", "switch",
            "this", "throw", "throws", "true", "try", "var", "void", "while", "yield");

    private final FlowPane buttons = new FlowPane();
    private final ToggleGroup selection = new ToggleGroup();
    private final ListGridModel<Line> lines = new ListGridModel<Line>().column("Code", Line::text);
    private final Grid grid = new Grid(lines);

    SourceView() {
        getStyleClass().add("source-view");
        buttons.getStyleClass().add("source-files");
        Font font = Font.font(Font.getFamilies().contains("Consolas") ? "Consolas"
                : Font.getFamilies().contains("Menlo") ? "Menlo" : "Monospaced", 12);
        grid.setFont(font);
        grid.setRowHeight(18);
        grid.setColumnHeaderVisible(false);
        grid.setRowHeaderVisible(true);
        grid.setSortable(false);
        grid.setColumnsReorderable(false);
        grid.setGridLineColor(Color.TRANSPARENT);
        grid.setAlternateCellBackground(null);
        grid.setAutoFitColumns(false);
        grid.getSelectionModel().setMode(SelectionMode.ROW);
        grid.setCellRendererProvider((row, column, value) -> code);
        setTop(buttons);
        setCenter(grid);
    }

    /** Shows these files, the first one right away. */
    void show(List<SourceFile> files) {
        buttons.getChildren().clear();
        for (SourceFile file : files) {
            ToggleButton button = new ToggleButton(file.name());
            button.setToggleGroup(selection);
            button.setOnAction(e -> {
                button.setSelected(true);
                showFile(file);
            });
            buttons.getChildren().add(button);
        }
        ((ToggleButton) buttons.getChildren().getFirst()).setSelected(true);
        showFile(files.getFirst());
    }

    /** The names of the files shown, for tests. */
    List<String> fileNames() {
        return buttons.getChildren().stream().map(button -> ((ToggleButton) button).getText()).toList();
    }

    private void showFile(SourceFile file) {
        List<Line> parsed = parse(file.text(), file.name().endsWith(".css"));
        lines.setAll(parsed);
        int longest = parsed.stream().mapToInt(line -> line.text().length()).max().orElse(0);
        double width = TextMetrics.width("0".repeat(longest), grid.getFont()) + 2 * grid.getCellPadding() + 8;
        grid.getColumn(0).setWidth(Math.max(width, 200));
        grid.getSelectionModel().clear();
        grid.setScrollX(0);
        grid.setScrollY(0);
    }

    /** Paints a line part by part in the colors of its kinds; the whole line counts for copying and tooltips. */
    private final CellRenderer code = (gc, cell) -> {
        if (cell.getModelRow() < 0) {
            return;
        }
        Line line = lines.getItem(cell.getModelRow());
        cell.fitText(line.text());
        double advance = TextMetrics.width("0", cell.getFont()); // the font is monospaced
        double x = cell.getX() + cell.getPadding();
        double y = cell.getY() + cell.getHeight() / 2;
        for (int i = 0; i < line.kinds().length; i++) {
            int start = line.starts()[i];
            int end = i + 1 < line.starts().length ? line.starts()[i + 1] : line.text().length();
            Kind kind = line.kinds()[i];
            gc.setFill(kind.color != null ? kind.color : cell.getTextFill());
            gc.fillText(line.text().substring(start, end), x + start * advance, y);
        }
    };

    /// Splits a file into lines and their colored parts. Comments, strings and text blocks may span lines; this is
    /// enough for the examples, not a full Java or CSS parser.
    private static List<Line> parse(String text, boolean css) {
        List<Line> result = new ArrayList<>();
        Kind open = null; // a comment or text block going on from the line before
        for (String raw : text.split("\n", -1)) {
            String line = raw.replace("\t", "    ");
            List<Integer> starts = new ArrayList<>();
            List<Kind> kinds = new ArrayList<>();
            int i = 0;
            while (i < line.length()) {
                int start = i;
                char c = line.charAt(i);
                Kind kind;
                if (open != null) {
                    String close = open == Kind.COMMENT ? "*/" : "\"\"\"";
                    int end = line.indexOf(close, i);
                    i = end < 0 ? line.length() : end + close.length();
                    kind = open;
                    open = end < 0 ? open : null;
                } else if (line.startsWith("/*", i)) {
                    open = Kind.COMMENT;
                    i += 2;
                    kind = Kind.COMMENT;
                    int end = line.indexOf("*/", i);
                    if (end >= 0) {
                        i = end + 2;
                        open = null;
                    } else {
                        i = line.length();
                    }
                } else if (!css && line.startsWith("//", i)) {
                    i = line.length();
                    kind = Kind.COMMENT;
                } else if (!css && line.startsWith("\"\"\"", i)) {
                    open = Kind.STRING;
                    i = line.length();
                    kind = Kind.STRING;
                } else if (c == '"' || c == '\'') {
                    i++;
                    while (i < line.length() && line.charAt(i) != c) {
                        i += line.charAt(i) == '\\' ? 2 : 1;
                    }
                    i = Math.min(line.length(), i + 1);
                    kind = Kind.STRING;
                } else if (Character.isDigit(c)) {
                    while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_'
                            || line.charAt(i) == '.')) {
                        i++;
                    }
                    kind = Kind.NUMBER;
                } else if (c == '@' || css && c == '.' || Character.isJavaIdentifierStart(c) || css && c == '-') {
                    i++;
                    while (i < line.length() && (Character.isJavaIdentifierPart(line.charAt(i))
                            || css && line.charAt(i) == '-')) {
                        i++;
                    }
                    String word = line.substring(start, i);
                    kind = c == '@' || css && c == '.' ? Kind.ANNOTATION
                            : css ? (c == '-' ? Kind.KEYWORD : Kind.PLAIN)
                            : KEYWORDS.contains(word) ? Kind.KEYWORD : Kind.PLAIN;
                } else {
                    i++;
                    kind = Kind.PLAIN;
                }
                if (!kinds.isEmpty() && kinds.getLast() == kind) {
                    continue; // the part before goes on
                }
                starts.add(start);
                kinds.add(kind);
            }
            result.add(new Line(line, starts.stream().mapToInt(Integer::intValue).toArray(),
                    kinds.toArray(Kind[]::new)));
        }
        return result;
    }
}
