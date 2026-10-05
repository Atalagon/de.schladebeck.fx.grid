package de.schladebeck.fx.grid.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class NumbersTest {

    @Test
    void neededFractionDigitsLeaveOutTrailingZerosAndBinaryNoise() {
        assertEquals(1, Numbers.neededFractionDigits(48065.7));
        assertEquals(0, Numbers.neededFractionDigits(3.0));
        assertEquals(3, Numbers.neededFractionDigits(0.125));
        assertEquals(1, Numbers.neededFractionDigits(0.1 + 0.2));
        assertEquals(1, Numbers.neededFractionDigits(1.1f));
        assertEquals(2, Numbers.neededFractionDigits(new BigDecimal("12.50")), "a BigDecimal needs its scale");
    }

    @Test
    void onlyFiniteDecimalsOfAReadableSizeAreDecimals() {
        assertTrue(Numbers.isDecimal(0.0));
        assertTrue(Numbers.isDecimal(new BigDecimal("1.5")));
        assertFalse(Numbers.isDecimal(7));
        assertFalse(Numbers.isDecimal(1e-7));
        assertFalse(Numbers.isDecimal(1e15));
        assertFalse(Numbers.isDecimal(Double.NaN));
    }
}
