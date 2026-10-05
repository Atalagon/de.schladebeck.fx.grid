package de.schladebeck.fx.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TsvTest {

    @Test
    void writesRangeRowByRowAndCleansValues() {
        String[][] shown = {
                {"a", "1", "x"},
                {"b\tc", null, "y"},
                {"line\nbreak", "3", "z"}};

        String text = Tsv.of(new int[] {1, 2}, new int[] {0, 1}, (row, column) -> shown[row][column]);

        assertEquals("b c\t\nline break\t3\n", text);
    }
}
