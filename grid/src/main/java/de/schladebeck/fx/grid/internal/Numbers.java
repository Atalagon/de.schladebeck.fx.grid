package de.schladebeck.fx.grid.internal;

import java.math.BigDecimal;

/** How decimal numbers are shown, the same in cells and in the editor. */
public final class Numbers {

    private Numbers() {
    }

    /** Whether a value is shown as a decimal number with a fraction; others are shown as they are. */
    public static boolean isDecimal(Object value) {
        if (!(value instanceof Double || value instanceof Float || value instanceof BigDecimal)) {
            return false;
        }
        double d = ((Number) value).doubleValue();
        double magnitude = Math.abs(d);
        return Double.isFinite(d) && (magnitude == 0 || magnitude >= 1e-4 && magnitude < 1e15);
    }

    /// Fraction digits worth showing: at most 10, and no more than the type holds exactly (about 15 significant
    /// digits for a double, 7 for a float), so 3703703.673 does not show as 3703703.6730000004.
    ///
    /// @param decimal a `BigDecimal`, `Double` or `Float`
    /// @return the number of fraction digits to show
    public static int fractionDigits(Object decimal) {
        if (decimal instanceof BigDecimal big) {
            return Math.clamp(big.scale(), 0, 10);
        }
        double magnitude = Math.abs(((Number) decimal).doubleValue());
        int integerDigits = magnitude < 1 ? 1 : (int) Math.floor(Math.log10(magnitude)) + 1;
        return Math.clamp((decimal instanceof Float ? 7 : 15) - integerDigits, 0, 10);
    }

    /// The fraction digits a decimal number needs, without trailing zeros: 1 for 48065.7, 0 for 3.0, at most
    /// [#fractionDigits]. A `BigDecimal` needs its scale.
    ///
    /// @param decimal a value [#isDecimal] accepts
    /// @return the number of fraction digits it needs
    public static int neededFractionDigits(Object decimal) {
        int digits = fractionDigits(decimal);
        if (decimal instanceof BigDecimal) {
            return digits;
        }
        // Below 10^15 even with all digits, so the long holds it.
        long scaled = Math.round(Math.abs(((Number) decimal).doubleValue()) * Math.pow(10, digits));
        while (digits > 0 && scaled % 10 == 0) {
            scaled /= 10;
            digits--;
        }
        return digits;
    }
}
