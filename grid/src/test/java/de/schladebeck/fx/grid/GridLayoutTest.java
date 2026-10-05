package de.schladebeck.fx.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.schladebeck.fx.grid.GridLayout.ColumnRange;
import de.schladebeck.fx.grid.model.SortKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GridLayoutTest {

    @Test
    void encodesAndDecodes() {
        GridLayout layout = new GridLayout(List.of(new ColumnRange(2, 2), new ColumnRange(0, 1),
                new ColumnRange(3, 99_999_999)), Map.of(0, 120.0, 2, 95.5), Set.of(1),
                List.of(new SortKey(2, false), new SortKey(0, true)), 1, 3, 1.5, Map.of(0, "ID", 2, "a;b,c:d=e%f"));
        String text = layout.encode();

        assertEquals("v=1;order=2,0-1,3-99999999;widths=0:120,2:95.5;hidden=1;sort=2:desc,0:asc;frozen=1;frozenRows=3"
                + ";zoom=1.5;names=0:ID,2:a%3Bb%2Cc%3Ad%3De%25f", text);
        assertEquals(layout, GridLayout.decode(text));
    }

    @Test
    void defaultsAreEmpty() {
        GridLayout natural = new GridLayout(List.of(), Map.of(), Set.of(), List.of(), 0, 0, 1, Map.of());

        assertEquals("v=1;order=;widths=;hidden=;sort=;frozen=0;frozenRows=0;zoom=1;names=", natural.encode());
        assertEquals(natural, GridLayout.decode(natural.encode()));
    }

    @Test
    void unreadablePartsGetDefaults() {
        GridLayout layout = GridLayout.decode("order=1,x;widths=3,4:NaN,5:-2;sort=nonsense;zoom=Infinity;unknown=1");

        assertEquals(List.of(), layout.columnOrder(), "unreadable order");
        assertEquals(Map.of(), layout.columnWidths(), "unreadable and impossible widths");
        assertEquals(List.of(), layout.sortKeys());
        assertEquals(List.of(new SortKey(3, false)), GridLayout.decode("sort=3:desc").sortKeys(), "older layouts");
        assertEquals(List.of(), GridLayout.decode("sort=-1:asc").sortKeys(), "unsorted in older layouts");
        assertEquals(1, layout.zoom(), "zoom must be a positive finite number");
    }

    @Test
    void nothingSavedRestoresNothing() {
        assertNull(GridLayout.decode(null));
        assertNull(GridLayout.decode("  "));
    }

    @Test
    void rangesMustHoldColumns() {
        assertThrows(IllegalArgumentException.class, () -> new ColumnRange(5, 4));
        assertThrows(IllegalArgumentException.class, () -> new ColumnRange(-1, 4));
    }
}
