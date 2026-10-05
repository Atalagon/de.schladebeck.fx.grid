package de.schladebeck.fx.grid.render;

import javafx.geometry.HPos;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;

/// Immutable style of a cell or a column header. A `null` component means "use the grid default".
/// Create instances once and share them: a [CellStyleProvider] should return constants,
/// not build a new style per cell.
///
/// `fontWeight` and `fontPosture` change the font the cell would have otherwise, its own `font` or the grid's,
/// and keep its family and size. So a bold cell stays as large as its neighbors when the theme changes the
/// grid's font size.
///
/// @param background  background color, or `null`
/// @param textFill    text color, or `null`
/// @param font        font, or `null`
/// @param fontWeight  weight such as [FontWeight#BOLD], or `null`
/// @param fontPosture posture such as [FontPosture#ITALIC], or `null`
/// @param alignment   horizontal alignment, or `null` to let the renderer decide
public record CellStyle(Color background, Color textFill, Font font, FontWeight fontWeight, FontPosture fontPosture,
                        HPos alignment) {

    /** Nothing of its own, the start for the {@code with...} methods: {@code CellStyle.DEFAULT.withTextFill(red)}. */
    public static final CellStyle DEFAULT = new CellStyle(null, null, null, null, null, null);

    /** A copy with another background. */
    public CellStyle withBackground(Color background) {
        return new CellStyle(background, textFill, font, fontWeight, fontPosture, alignment);
    }

    /** A copy with another text color. */
    public CellStyle withTextFill(Color textFill) {
        return new CellStyle(background, textFill, font, fontWeight, fontPosture, alignment);
    }

    /** A copy with another font. */
    public CellStyle withFont(Font font) {
        return new CellStyle(background, textFill, font, fontWeight, fontPosture, alignment);
    }

    /** A copy with another font weight, for example bold. */
    public CellStyle withFontWeight(FontWeight fontWeight) {
        return new CellStyle(background, textFill, font, fontWeight, fontPosture, alignment);
    }

    /** A copy with another font posture, for example italic. */
    public CellStyle withFontPosture(FontPosture fontPosture) {
        return new CellStyle(background, textFill, font, fontWeight, fontPosture, alignment);
    }

    /** A copy with another horizontal alignment. */
    public CellStyle withAlignment(HPos alignment) {
        return new CellStyle(background, textFill, font, fontWeight, fontPosture, alignment);
    }
}
