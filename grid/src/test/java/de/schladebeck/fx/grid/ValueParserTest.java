package de.schladebeck.fx.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ValueParserTest {

    private final Locale before = Locale.getDefault();

    enum Size { SMALL, LARGE }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(before);
    }

    @Test
    void textAndBlanks() {
        assertEquals("  keeps spaces ", ValueParser.parse("  keeps spaces ", String.class));
        assertNull(ValueParser.parse("   ", Integer.class), "blank is no value");
        assertEquals(42, ValueParser.parse(" 42 ", Integer.class));
        assertEquals(42, ValueParser.parse("42", int.class), "primitive columns count as their wrapper");
        assertSame(ValueParser.INVALID, ValueParser.parse("4x", Long.class));
    }

    @Test
    void decimalsInTheLocaleOrPlain() {
        Locale.setDefault(Locale.GERMANY);
        assertEquals(12.5, ValueParser.parse("12,5", Double.class));
        assertEquals(1.5, ValueParser.parse("1.5", Double.class), "plain Java format still works");
        assertSame(ValueParser.INVALID, ValueParser.parse("1.234,5", Double.class), "grouping is not guessed");
        assertEquals(new BigDecimal("3.25"), ValueParser.parse("3,25", BigDecimal.class));
        assertEquals("3703703,673", ValueParser.format(3703703.6730000004));

        Locale.setDefault(Locale.US);
        assertEquals(12.5, ValueParser.parse("12.5", Double.class));
        assertSame(ValueParser.INVALID, ValueParser.parse("12,5", Double.class));
    }

    @Test
    void booleansEnumsAndDates() {
        assertEquals(true, ValueParser.parse("TRUE", Boolean.class));
        assertSame(ValueParser.INVALID, ValueParser.parse("yes", Boolean.class));
        assertEquals(Size.LARGE, ValueParser.parse("large", Size.class));
        assertSame(ValueParser.INVALID, ValueParser.parse("medium", Size.class));
        assertEquals("SMALL", ValueParser.format(Size.SMALL));

        Locale.setDefault(Locale.GERMANY);
        assertEquals(LocalDate.of(2024, Month.JANUARY, 31), ValueParser.parse("2024-01-31", LocalDate.class));
        assertEquals(LocalDate.of(2024, Month.JANUARY, 31), ValueParser.parse("31.01.2024", LocalDate.class));
        assertSame(ValueParser.INVALID, ValueParser.parse("31/01/2024", LocalDate.class));
    }

    @Test
    void onlyKnownTypesAreEdited() {
        assertTrue(ValueParser.supports(double.class));
        assertTrue(ValueParser.supports(Size.class));
        assertFalse(ValueParser.supports(TimeUnit[].class));
        assertFalse(ValueParser.supports(Thread.class));
    }
}
