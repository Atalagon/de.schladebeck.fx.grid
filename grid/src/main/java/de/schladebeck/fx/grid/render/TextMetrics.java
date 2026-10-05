package de.schladebeck.fx.grid.render;

import de.schladebeck.fx.grid.internal.TextFitter;
import javafx.scene.text.Font;

/// Fast text measurement with the same rules the grid uses to cut and wrap text, e.g. for a
/// [RowHeightProvider][de.schladebeck.fx.grid.RowHeightProvider] that makes rows as high as their wrapped text:
/// ```java
/// grid.setRowHeightProvider(row -> {
///     Font font = grid.getFont();
///     double width = grid.getColumn(NOTES).getWidth() - 2 * grid.getCellPadding();
///     return TextMetrics.lineCount(notes(row), font, width) * TextMetrics.lineHeight(font) + 8;
/// });
/// ```
/// The font and sizes are read inside the provider: they come from CSS, which applies only once the grid is in a
/// scene, and they change with the theme. For the JavaFX application thread only.
public final class TextMetrics {

    private static TextFitter fitter;

    private TextMetrics() {
    }

    /** Width of {@code text} on one line. */
    public static double width(String text, Font font) {
        return fitter().width(text, font);
    }

    /** Height of one line of text. */
    public static double lineHeight(Font font) {
        return fitter().lineHeight(font);
    }

    /** Number of lines {@code text} needs when wrapped to {@code width}, at least 1. */
    public static int lineCount(String text, Font font, double width) {
        return fitter().wrap(text, font, width, Integer.MAX_VALUE).size();
    }

    private static TextFitter fitter() {
        if (fitter == null) {
            fitter = new TextFitter();
        }
        return fitter;
    }
}
