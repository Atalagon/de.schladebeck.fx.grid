package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.model.GridModel;
import java.util.function.IntUnaryOperator;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.stage.Window;
import javafx.util.StringConverter;

/// In-place editing: a text field laid over the cell only while editing. Typing a character starts editing with
/// it, Enter or leaving the field commits, Tab commits and edits the next cell (not check boxes), Escape cancels.
/// Input that cannot be converted, or that the model rejects, stays in the field with a message; a click in the
/// grid or the focus coming back returns to the field until the input is valid or cancelled. Values go through the
/// column's converter or [ValueParser] to [GridModel#setValueAt].
///
/// The editor holds its model row and column, so it stays on its cell while rows are inserted, deleted, sorted or
/// filtered, and cancels when the cell is gone.
final class CellEditor {

    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");

    private final Grid grid;
    private final GridSkin skin;
    private final TextField field = new TextField();
    private final Tooltip problem = new Tooltip();
    private final Rectangle clip = new Rectangle();
    private GridModel model;
    private int modelRow = -1;
    private int modelColumn;
    private Class<?> type;
    private StringConverter<Object> converter;
    private String initialText;
    private boolean stopping;
    private Font baseFont;
    private double fontZoom;

    CellEditor(Grid grid, GridSkin skin) {
        this.grid = grid;
        this.skin = skin;
        field.getStyleClass().add("grid-editor");
        field.setClip(clip);
        field.setOnAction(e -> commit());
        field.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case ESCAPE -> cancel();
                case TAB -> commitAndMove(e.isShiftDown() ? -1 : 1);
                case F2 -> {
                    // already editing
                }
                default -> {
                    return;
                }
            }
            e.consume();
        });
        field.focusedProperty().addListener((o, was, focused) -> {
            Window window = field.getScene() == null ? null : field.getScene().getWindow();
            // Another window taking the focus is no reason to end the edit.
            if (!focused && isEditing() && !stopping && (window == null || window.isFocused())) {
                commit();
            }
        });
        field.textProperty().addListener(o -> {
            field.pseudoClassStateChanged(INVALID, false);
            problem.hide();
        });
        // Coming back to the grid with an edit still open, for example after the input was not valid.
        grid.focusedProperty().addListener((o, was, focused) -> {
            if (focused && isEditing() && !stopping && field.isVisible()) {
                focus();
            }
        });
    }

    /** Gives the keyboard to the field, for example after a click in the grid while the input is not valid. */
    void focus() {
        field.requestFocus();
    }

    boolean isEditing() {
        return modelRow >= 0;
    }

    TextField field() {
        return field;
    }

    Tooltip problem() {
        return problem;
    }

    /// Starts editing a view cell if the grid is editable, the model allows it and the value can be edited as
    /// text. Booleans without a converter are toggled right away instead. Returns false if the cell is not edited.
    boolean start(int row, int column) {
        GridModel data = grid.getModel();
        if (data == null || !grid.isEditable() || row < 0 || column < 0 || row >= grid.getViewRowCount()
                || column >= grid.getViewColumnCount()) {
            return false;
        }
        int r = grid.viewToModelRow(row);
        int c = grid.viewToModelColumn(column);
        if (!data.isCellEditable(r, c)) {
            return false;
        }
        Object value = data.getValueAt(r, c);
        StringConverter<Object> columnConverter = grid.settings().get(c, settings -> settings.converter);
        if (columnConverter == null && value instanceof Boolean checked) {
            try {
                data.setValueAt(!checked, r, c);
            } catch (RuntimeException e) {
                grid.report("GridModel.setValueAt", e);
            }
            return true;
        }
        Class<?> valueType = value instanceof Enum<?> constant ? constant.getDeclaringClass()
                : value != null ? value.getClass() : data.getColumnClass(c);
        if (columnConverter == null && !ValueParser.supports(valueType)) {
            return false;
        }
        if (isEditing() && !commit()) {
            focus();
            return false;
        }
        model = data;
        modelRow = r;
        modelColumn = c;
        type = valueType;
        converter = columnConverter;
        initialText = converter != null ? converter.toString(value) : ValueParser.format(value);
        field.setText(initialText);
        field.setAlignment(Number.class.isAssignableFrom(ValueParser.boxed(type)) ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        skin.showEditor(field);
        field.requestFocus();
        field.selectAll();
        return true;
    }

    /** Writes the value back. Returns false and keeps editing if the text is not valid or the model refuses it. */
    boolean commit() {
        if (!isEditing()) {
            return false;
        }
        if (field.getText().equals(initialText)) {
            stop();
            return true;
        }
        Object value;
        try {
            String text = field.getText();
            value = converter != null ? converter.fromString(text) : ValueParser.parse(text, type);
        } catch (RuntimeException e) {
            value = ValueParser.INVALID;
        }
        if (value == ValueParser.INVALID) {
            // The value the cell had is an example of what it accepts.
            invalid(initialText.isBlank() ? Texts.get("edit.invalid", field.getText())
                    : Texts.get("edit.invalidExample", field.getText(), initialText));
            return false;
        }
        try {
            model.setValueAt(value, modelRow, modelColumn);
        } catch (RuntimeException e) {
            invalid(Texts.get("edit.rejected", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return false;
        }
        stop();
        return true;
    }

    void cancel() {
        if (isEditing()) {
            stop();
        }
    }

    /// Rows were inserted or deleted and `shift` maps old model rows to new ones (-1 for deleted rows), or all
    /// rows changed (`shift` is `null`): the editor follows its row, or stops if the row is gone.
    void rowsMoved(IntUnaryOperator shift) {
        if (!isEditing()) {
            return;
        }
        int moved = shift == null ? -1 : shift.applyAsInt(modelRow);
        if (moved < 0) {
            cancel();
        } else {
            modelRow = moved;
        }
    }

    /// Places the field over its cell, cut to the part that is not under a header or the frozen columns. A cell
    /// scrolled out of view entirely gives the keyboard back to the grid, so nobody types into a hidden field.
    void layout(GridGeometry g, double canvasX, double canvasY) {
        if (!isEditing()) {
            return;
        }
        int row = grid.getModel() == model ? grid.modelToViewRow(modelRow) : -1;
        int column = grid.modelToViewColumn(modelColumn);
        if (row < 0 || column < 0) {
            cancel();
            return;
        }
        Bounds b = g.cellBounds(row, column);
        double zoom = g.zoom();
        double left = (g.rowHeaderWidth() + (column < g.frozenColumns() ? 0 : g.frozenWidth())) * zoom;
        double x1 = Math.max(b.getMinX(), left);
        double top = (g.headerHeight() + (row < g.frozenRows() ? 0 : g.frozenHeight())) * zoom;
        double y1 = Math.max(b.getMinY(), top);
        double x2 = Math.min(b.getMaxX(), g.width() * zoom);
        double y2 = Math.min(b.getMaxY(), g.height() * zoom);
        boolean visible = x2 > x1 && y2 > y1;
        field.setVisible(visible);
        if (!visible && field.isFocused()) {
            grid.requestFocus();
        }
        Font font = grid.getFont();
        if (font != baseFont || zoom != fontZoom) {
            baseFont = font;
            fontZoom = zoom;
            field.setFont(Font.font(font.getFamily(), font.getSize() * zoom));
        }
        field.resizeRelocate(canvasX + b.getMinX(), canvasY + b.getMinY(), b.getWidth(), b.getHeight());
        clip.setX(x1 - b.getMinX());
        clip.setY(y1 - b.getMinY());
        clip.setWidth(Math.max(0, x2 - x1));
        clip.setHeight(Math.max(0, y2 - y1));
    }

    /// Tab and Shift+Tab: commits, moves to the next or previous cell in the row and edits it, unless it is a check
    /// box, which editing would toggle.
    private void commitAndMove(int step) {
        int row = grid.modelToViewRow(modelRow);
        int column = grid.modelToViewColumn(modelColumn);
        if (commit() && row >= 0 && column >= 0) {
            int next = Math.clamp(column + step, 0, grid.getViewColumnCount() - 1);
            grid.getSelectionModel().select(row, next);
            grid.scrollTo(row, next);
            if (next != column && !(grid.getViewValueAt(row, next) instanceof Boolean)) {
                start(row, next);
            }
        }
    }

    /// Typing on a cell: edits it, starting with the typed text instead of its value. Check boxes are not edited by
    /// typing. Returns whether editing started.
    boolean startTyping(int row, int column, String typed) {
        if (grid.getViewValueAt(row, column) instanceof Boolean || !start(row, column) || !isEditing()) {
            return false;
        }
        field.setText(typed);
        field.end();
        return true;
    }

    private void invalid(String message) {
        field.pseudoClassStateChanged(INVALID, true);
        problem.setText(message);
        Point2D below = field.localToScreen(0, field.getHeight());
        if (below != null) {
            problem.show(field, below.getX(), below.getY());
        }
    }

    private void stop() {
        stopping = true;
        modelRow = -1;
        model = null;
        converter = null;
        problem.hide();
        field.pseudoClassStateChanged(INVALID, false);
        skin.hideEditor(field);
        grid.requestFocus();
        stopping = false;
    }
}
