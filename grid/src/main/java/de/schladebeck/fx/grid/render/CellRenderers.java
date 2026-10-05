package de.schladebeck.fx.grid.render;

import de.schladebeck.fx.grid.internal.Numbers;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Format;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javafx.geometry.HPos;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/// Built-in renderers, factories for formatted text, and text helpers for custom renderers. All of them leave
/// cutting text to [CellContext#fitText(String)], so cut-off text gets a tooltip and counts for column fitting.
public final class CellRenderers {

    /** Opacity of check boxes that cannot be edited, as Modena shows disabled controls. */
    private static final double DISABLED_OPACITY = 0.4;
    /** Opacity of the track behind a progress bar. */
    private static final double TRACK_OPACITY = 0.15;
    /** Opacity of a data bar, so the number on it and the selection under it stay visible. */
    private static final double DATA_BAR_OPACITY = 0.35;

    /** {@code toString()}, left aligned. */
    public static final CellRenderer TEXT = (gc, cell) -> {
        if (cell.getValue() != null) {
            drawText(gc, cell, cell.getValue().toString(), HPos.LEFT);
        }
    };

    /// Numbers, right aligned. Decimal numbers (`Double`, `Float`, `BigDecimal`) are written in the default
    /// locale's format, grouped, with up to 10 fraction digits and no more than the type holds exactly (about 15
    /// significant digits for a double), and with as many as the others on screen in the column need
    /// ([CellContext#getFractionDigits()]), so their decimal separators line up. Whole numbers are written as they
    /// are, so IDs and years stay ungrouped. A number too wide for its column shows `###` instead of a misleading
    /// shortened number; its tooltip shows it in full.
    public static final CellRenderer NUMBER = (gc, cell) -> {
        if (cell.getValue() != null) {
            drawNumber(gc, cell, numberText(cell.getValue(), cell.getFractionDigits()), HPos.RIGHT);
        }
    };

    /// `toString()` wrapped over as many lines as the row is high, left aligned. Give rows room with
    /// [de.schladebeck.fx.grid.Grid#setRowHeightProvider] or a larger row height.
    public static final CellRenderer WRAPPED_TEXT = (gc, cell) -> {
        if (cell.getValue() != null) {
            drawWrappedText(gc, cell, cell.getValue().toString(), HPos.LEFT);
        }
    };

    /// A centered check box as large as the font, on whole device pixels so it stays sharp at any scaling. Muted
    /// like a disabled check box where the cell cannot be edited ([CellContext#isEditable()]).
    public static final CellRenderer BOOLEAN = (gc, cell) -> {
        if (!(cell.getValue() instanceof Boolean checked)) {
            return;
        }
        double size = Math.max(6, Math.min(Math.round(cell.getFont().getSize()), cell.getHeight() - 4));
        double x = cell.snap(cell.getX() + (cell.getWidth() - size) / 2);
        double y = cell.snap(cell.getY() + (cell.getHeight() - size) / 2);
        double right = cell.snap(x + size);
        double bottom = cell.snap(y + size);
        double line = cell.snap(x + 1) - x;
        double t = line > 0 ? line : 1;
        Color color = cell.isEditable() ? cell.getTextFill()
                : cell.getTextFill().deriveColor(0, 1, 1, DISABLED_OPACITY);
        // The frame's parts do not overlap, so a see-through color stays even.
        gc.setFill(color);
        gc.fillRect(x, y, right - x, t);
        gc.fillRect(x, bottom - t, right - x, t);
        gc.fillRect(x, y + t, t, bottom - y - 2 * t);
        gc.fillRect(right - t, y + t, t, bottom - y - 2 * t);
        if (checked) {
            double w = right - x;
            gc.setStroke(color);
            gc.setLineWidth(Math.max(1.5, w / 7));
            // Two lines, not a path: JavaFX draws lines on the graphics card, paths on the CPU, which costs up to
            // half the frame time with thousands of check boxes on screen.
            gc.strokeLine(x + w * 0.22, y + w * 0.52, x + w * 0.42, y + w * 0.72);
            gc.strokeLine(x + w * 0.42, y + w * 0.72, x + w * 0.78, y + w * 0.3);
        }
    };

    /** {@link #progress(Color)} in the text color. */
    public static final CellRenderer PROGRESS = progress(null);

    /// The default locale's formats for decimal numbers by fraction digits, 0 to 10: first the ones that show only
    /// the digits needed, then the ones that show all of them.
    private static NumberFormat[] decimals;
    private static Locale decimalsLocale;
    /** The default locale's format for percentages, see {@link #progress(Color)}. */
    private static NumberFormat percent;
    private static Locale percentLocale;

    private CellRenderers() {
    }

    /** Numbers formatted with a {@link DecimalFormat} pattern such as {@code "#,##0.00"}, right aligned. */
    public static CellRenderer number(String pattern) {
        return format(new DecimalFormat(pattern), HPos.RIGHT);
    }

    /// Numbers formatted with a [DecimalFormat] pattern in a given locale's symbols, right aligned, e.g.
    /// `number("#,##0.00", Locale.GERMANY)` for `1.234,50`.
    ///
    /// @param pattern the pattern
    /// @param locale  the locale whose decimal and grouping separators are used
    /// @return the renderer
    public static CellRenderer number(String pattern, Locale locale) {
        return format(new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale)), HPos.RIGHT);
    }

    /** Values formatted with any {@link Format}, left aligned, e.g. a {@code DateTimeFormatter} as Format. */
    public static CellRenderer format(Format format) {
        return format(format, HPos.LEFT);
    }

    /** Values formatted with any {@link Format}; numbers too wide for their column show {@code ###}. */
    public static CellRenderer format(Format format, HPos alignment) {
        return (gc, cell) -> {
            Object value = cell.getValue();
            if (value instanceof Number) {
                drawNumber(gc, cell, format.format(value), alignment);
            } else if (value != null) {
                drawText(gc, cell, format.format(value), alignment);
            }
        };
    }

    /// Text computed from the value, left aligned. The value's type comes from the lambda:
    /// `CellRenderers.text((Person p) -> p.name())`.
    ///
    /// @param toText turns a value into text
    /// @param <T>    the type of the column's values
    /// @return the renderer
    @SuppressWarnings("unchecked")
    public static <T> CellRenderer text(Function<? super T, String> toText) {
        return (gc, cell) -> {
            if (cell.getValue() != null) {
                drawText(gc, cell, toText.apply((T) cell.getValue()), HPos.LEFT);
            }
        };
    }

    /// Numbers from 0 to 1 as a thin bar with round ends on a light track of the same color, and as a percentage
    /// at the right in the default locale's format, such as `45 %`. The bar shows values below 0 or above 1 as 0
    /// or 1, the text as they are. Fitting the column gives the bar four times the font size. Use e.g.
    /// `CellRenderers.progress(Color.web("#2da44e"))`.
    ///
    /// @param color the bar's color, or `null` for the text color
    /// @return the renderer
    public static CellRenderer progress(Color color) {
        return (gc, cell) -> {
            if (!(cell.getValue() instanceof Number number)) {
                return;
            }
            Font font = cell.getFont();
            String text = percentFormat().format(number.doubleValue());
            double height = Math.max(2, Math.round(font.getSize() / 2));
            double gap = font.getSize() / 2;
            // The room 100 % needs is kept for the text, so the bars of all rows end in the same place.
            double bar = cell.getWidth() - 2 * cell.getPadding() - gap
                    - Math.max(TextMetrics.width(percentFormat().format(1), font), TextMetrics.width(text, font));
            if (bar >= 2 * height) { // shorter ones look like dots
                double x = cell.getX() + cell.getPadding();
                double y = cell.getY() + (cell.getHeight() - height) / 2;
                gc.setFill(color != null ? color : cell.getTextFill());
                gc.setGlobalAlpha(TRACK_OPACITY);
                gc.fillRoundRect(x, y, bar, height, height, height);
                gc.setGlobalAlpha(1);
                double fraction = Math.clamp(number.doubleValue(), 0, 1);
                if (fraction > 0) { // not for NaN either
                    gc.fillRoundRect(x, y, bar * fraction, height, height, height);
                }
                cell.indent(Math.min(bar, 4 * font.getSize()) + gap);
            }
            prepare(gc, cell, HPos.RIGHT, VPos.CENTER);
            gc.fillText(cell.fitText(text), alignedX(cell, HPos.RIGHT), cell.getY() + cell.getHeight() / 2);
        };
    }

    /// Numbers as [#NUMBER] writes them, on a bar as long as the value's place between `min` and `max`, like
    /// Excel's data bars. The bar is see-through, so the number stays readable and a selected cell still shows
    /// its selection. Use e.g. `CellRenderers.dataBar(0, 100, Color.web("#0969da"))`.
    ///
    /// @param min   the value with no bar; smaller values have none either
    /// @param max   the value whose bar fills the cell; larger values fill it too
    /// @param color the bar's color, which the renderer makes see-through
    /// @return the renderer
    /// @throws IllegalArgumentException if `min` is not less than `max`
    public static CellRenderer dataBar(double min, double max, Color color) {
        if (!(min < max)) {
            throw new IllegalArgumentException("min must be less than max: " + min + ", " + max);
        }
        Color fill = color.deriveColor(0, 1, 1, DATA_BAR_OPACITY);
        return (gc, cell) -> {
            if (!(cell.getValue() instanceof Number number)) {
                return;
            }
            double fraction = Math.clamp((number.doubleValue() - min) / (max - min), 0, 1);
            if (fraction > 0) {
                double inset = Math.max(1, Math.round(cell.getFont().getSize() / 6));
                double x = cell.snap(cell.getX() + inset);
                double y = cell.snap(cell.getY() + inset);
                gc.setFill(fill);
                gc.fillRect(x, y, (cell.snap(cell.getX() + cell.getWidth() - inset) - x) * fraction,
                        cell.snap(cell.getY() + cell.getHeight() - inset) - y);
            }
            drawNumber(gc, cell, numberText(number, cell.getFractionDigits()), HPos.RIGHT);
        };
    }

    /// The value's text on a label with round ends, such as a status, in a color picked for the value. The text
    /// is black or white, whichever reads better on that color. Use e.g.
    /// `CellRenderers.badge((Status s) -> s.color())`.
    ///
    /// @param background the label's color for a value; `null` leaves out the label but not the text
    /// @param <T>        the type of the column's values
    /// @return the renderer
    public static <T> CellRenderer badge(Function<? super T, Color> background) {
        return badge(Object::toString, background);
    }

    /// Like [#badge(Function)], with the text computed from the value. Use e.g.
    /// `CellRenderers.badge((Status s) -> s.label(), (Status s) -> s.color())`.
    ///
    /// @param toText     turns a value into text; `null` paints nothing
    /// @param background the label's color for a value; `null` leaves out the label but not the text
    /// @param <T>        the type of the column's values
    /// @return the renderer
    @SuppressWarnings("unchecked")
    public static <T> CellRenderer badge(Function<? super T, String> toText, Function<? super T, Color> background) {
        return (gc, cell) -> {
            T value = (T) cell.getValue();
            String text = value == null ? null : toText.apply(value);
            if (text == null) {
                return;
            }
            Color fill = background.apply(value);
            Font font = cell.getFont();
            double height = Math.min(cell.getHeight() - 2, Math.round(font.getSize() * 1.6));
            double x = cell.getX() + cell.getPadding();
            double room = cell.getWidth() - 2 * cell.getPadding();
            cell.indent(height); // the round ends: the text is fitted between them, and fitting the column counts them
            String fitted = cell.fitText(oneLine(text));
            if (fitted.isEmpty()) {
                return;
            }
            double width = TextMetrics.width(fitted, font) + height;
            HPos alignment = cell.getAlignment() != null ? cell.getAlignment() : HPos.LEFT;
            x += (room - width) * switch (alignment) {
                case LEFT -> 0;
                case CENTER -> 0.5;
                case RIGHT -> 1;
            };
            double y = cell.getY() + (cell.getHeight() - height) / 2;
            prepare(gc, cell, HPos.CENTER, VPos.CENTER);
            if (fill != null) {
                gc.setFill(fill);
                gc.fillRoundRect(x, y, width, height, height, height);
                double luma = 0.21 * fill.getRed() + 0.71 * fill.getGreen() + 0.07 * fill.getBlue(); // as grayscale()
                gc.setFill(luma > 0.5 ? Color.BLACK : Color.WHITE);
            }
            gc.fillText(fitted, x + width / 2, y + height / 2);
        };
    }

    /// A small line chart of a `double[]` or a list of numbers across the cell between the paddings, from the
    /// smallest value at the bottom to the largest at the top, with a dot at the last value. Fitting the column
    /// gives it six times the font size. Use e.g. `CellRenderers.sparkline(Color.web("#0969da"))`.
    ///
    /// @param color the color of the line and the dot
    /// @return the renderer
    public static CellRenderer sparkline(Color color) {
        return (gc, cell) -> {
            double[] values = numbers(cell.getValue());
            if (values == null) {
                return;
            }
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (double value : values) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            if (!Double.isFinite(max - min)) {
                return; // no values, or one that is not a finite number
            }
            double size = cell.getFont().getSize();
            double dot = Math.max(1.5, size / 6);
            double height = Math.max(0, Math.min(cell.getHeight(), size * 1.6) - 2 * dot);
            double left = cell.getX() + cell.getPadding();
            double width = cell.getWidth() - 2 * cell.getPadding();
            double bottom = cell.getY() + (cell.getHeight() + height) / 2;
            double x = 0;
            double y = 0;
            gc.save();
            gc.setStroke(color);
            gc.setLineWidth(Math.max(1, size / 10));
            gc.setLineJoin(StrokeLineJoin.ROUND);
            gc.beginPath();
            for (int i = 0; i < values.length; i++) {
                x = left + (values.length > 1 ? width * i / (values.length - 1) : width);
                y = bottom - (max > min ? (values[i] - min) / (max - min) : 0.5) * height;
                if (i == 0) {
                    gc.moveTo(x, y);
                } else {
                    gc.lineTo(x, y);
                }
            }
            gc.stroke();
            gc.restore();
            gc.setFill(color);
            gc.fillOval(x - dot, y - dot, 2 * dot, 2 * dot);
            cell.indent(6 * size); // the width the sparkline wants when the column is fitted
        };
    }

    /// Draws text over as many lines as fit the cell, the block of lines vertically centered. Wraps at spaces and
    /// line breaks; the last line ends in an ellipsis if the text needs more lines than fit.
    /// The cell style's alignment wins over `defaultAlignment`.
    public static void drawWrappedText(GraphicsContext gc, CellContext cell, String text, HPos defaultAlignment) {
        List<String> lines = cell.wrapText(text);
        HPos alignment = cell.getAlignment() != null ? cell.getAlignment() : defaultAlignment;
        double lineHeight = cell.getLineHeight();
        double top = cell.getY() + Math.max(0, (cell.getHeight() - lines.size() * lineHeight) / 2);
        prepare(gc, cell, alignment, VPos.TOP);
        double x = alignedX(cell, alignment);
        for (int i = 0; i < lines.size(); i++) {
            gc.fillText(lines.get(i), x, top + i * lineHeight);
        }
    }

    /// Draws a single line of text, vertically centered and shortened with an ellipsis if needed. Line breaks in
    /// the text are shown as spaces; [#drawWrappedText] keeps them. The cell style's alignment wins over
    /// `defaultAlignment`.
    public static void drawText(GraphicsContext gc, CellContext cell, String text, HPos defaultAlignment) {
        draw(gc, cell, cell.fitText(oneLine(text)), defaultAlignment);
    }

    private static String oneLine(String text) {
        if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    /// A number as [#NUMBER] writes it, a decimal number with at least `minDigits` fraction digits where it
    /// holds them; whole numbers and other values as their `toString()`.
    static String numberText(Object value, int minDigits) {
        if (!Numbers.isDecimal(value)) {
            return value.toString();
        }
        if (value instanceof BigDecimal) {
            // Its scale means something, e.g. cents: 12.50 stays 12.50. More zeros are exact too.
            int digits = Math.min(Math.max(Numbers.fractionDigits(value), minDigits), 10);
            return decimalFormat(digits, digits).format(value);
        }
        int digits = Numbers.fractionDigits(value);
        return decimalFormat(Math.min(minDigits, digits), digits).format(value);
    }

    /** The default locale's format with {@code min} to {@code max} fraction digits. */
    private static NumberFormat decimalFormat(int min, int max) {
        Locale locale = Locale.getDefault(Locale.Category.FORMAT);
        if (!locale.equals(decimalsLocale)) {
            decimals = new NumberFormat[11 * 11];
            decimalsLocale = locale;
        }
        int i = min * 11 + max;
        if (decimals[i] == null) {
            decimals[i] = NumberFormat.getNumberInstance(locale);
            decimals[i].setMaximumFractionDigits(max);
            decimals[i].setMinimumFractionDigits(min);
        }
        return decimals[i];
    }

    private static NumberFormat percentFormat() {
        Locale locale = Locale.getDefault(Locale.Category.FORMAT);
        if (!locale.equals(percentLocale)) {
            percent = NumberFormat.getPercentInstance(locale);
            percentLocale = locale;
        }
        return percent;
    }

    /** The numbers of a {@code double[]} or of a list of numbers, or {@code null} for anything else. */
    private static double[] numbers(Object value) {
        if (!(value instanceof List<?> list)) {
            return value instanceof double[] array ? array : null;
        }
        double[] numbers = new double[list.size()];
        int i = 0;
        for (Object element : list) {
            if (!(element instanceof Number number)) {
                return null;
            }
            numbers[i++] = number.doubleValue();
        }
        return numbers;
    }

    /** Like {@link #drawText}, but a number that does not fit is shown as {@code ###}, never shortened. */
    private static void drawNumber(GraphicsContext gc, CellContext cell, String text, HPos defaultAlignment) {
        String fitted = cell.fitText(text);
        if (!fitted.equals(text)) {
            double hash = TextMetrics.width("#", cell.getFont());
            int count = (int) Math.floor((cell.getWidth() - 2 * cell.getPadding()) / hash);
            fitted = "#".repeat(Math.max(0, count));
        }
        draw(gc, cell, fitted, defaultAlignment);
    }

    private static void draw(GraphicsContext gc, CellContext cell, String fitted, HPos defaultAlignment) {
        if (fitted.isEmpty()) {
            return;
        }
        HPos alignment = cell.getAlignment() != null ? cell.getAlignment() : defaultAlignment;
        prepare(gc, cell, alignment, VPos.CENTER);
        gc.fillText(fitted, alignedX(cell, alignment), cell.getY() + cell.getHeight() / 2);
    }

    private static void prepare(GraphicsContext gc, CellContext cell, HPos alignment, VPos baseline) {
        gc.setFont(cell.getFont());
        gc.setFill(cell.getTextFill());
        gc.setTextBaseline(baseline);
        gc.setTextAlign(switch (alignment) {
            case LEFT -> TextAlignment.LEFT;
            case CENTER -> TextAlignment.CENTER;
            case RIGHT -> TextAlignment.RIGHT;
        });
    }

    /** Where text with this alignment starts (left), is centered, or ends (right). */
    private static double alignedX(CellContext cell, HPos alignment) {
        return switch (alignment) {
            case LEFT -> cell.getX() + cell.getPadding();
            case CENTER -> cell.getX() + cell.getWidth() / 2;
            case RIGHT -> cell.getX() + cell.getWidth() - cell.getPadding();
        };
    }
}
