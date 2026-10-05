package de.schladebeck.fx.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GridSelectionModelTest {

    private final GridSelectionModel selection = new GridSelectionModel();

    GridSelectionModelTest() {
        size(100, 10);
    }

    private void size(int rows, int columns) {
        selection.setSize(() -> rows, () -> columns);
    }

    @Test
    void extendSpansFromAnchorInAnyDirection() {
        selection.select(10, 5);
        selection.extend(2, 1);

        assertEquals(List.of(new CellRange(2, 1, 10, 5)), selection.getRanges());
        assertEquals(10, selection.getAnchorRow());
        assertEquals(2, selection.getFocusedRow());
        assertTrue(selection.isSelected(6, 3));
        assertFalse(selection.isSelected(11, 3));
    }

    @Test
    void addStartsNewRangeAndExtendOnlyChangesIt() {
        selection.select(0, 0);
        selection.extend(1, 1);
        selection.add(5, 5);
        selection.extend(6, 6);

        assertEquals(List.of(new CellRange(0, 0, 1, 1), new CellRange(5, 5, 6, 6)), selection.getRanges());
        assertTrue(selection.isSelected(1, 1));
        assertFalse(selection.isSelected(3, 3));
    }

    @Test
    void hugeRangeIsOneEntry() {
        size(1_000_000, 1_000);
        selection.select(0, 0);
        selection.selectRange(new CellRange(0, 0, 999_999, 999));

        assertEquals(1, selection.getRanges().size());
        assertEquals(1_000_000_000L, selection.getRanges().getFirst().cellCount());
        assertEquals(0, selection.getFocusedRow());
    }

    @Test
    void extendWithoutAnchorSelects() {
        selection.extend(3, 4);

        assertEquals(List.of(new CellRange(3, 4, 3, 4)), selection.getRanges());
        assertEquals(3, selection.getAnchorRow());
    }

    @Test
    void notifiesListeners() {
        int[] calls = {0};
        selection.addListener(o -> calls[0]++);
        selection.select(1, 1);
        selection.clear();

        assertEquals(2, calls[0]);
        assertTrue(selection.isEmpty());
        assertEquals(-1, selection.getFocusedRow());
    }

    @Test
    void singleCellModeKeepsOneCell() {
        selection.setMode(SelectionMode.SINGLE_CELL);
        selection.select(1, 1);
        selection.extend(5, 5);
        selection.add(7, 7);
        selection.selectAll();

        assertEquals(List.of(new CellRange(7, 7, 7, 7)), selection.getRanges());
    }

    @Test
    void rowModeSelectsWholeRows() {
        selection.setMode(SelectionMode.ROW);
        selection.select(3, 4);
        selection.extend(5, 2);
        assertEquals(5, selection.getFocusedRow(), "focus stays a single cell");
        assertEquals(2, selection.getFocusedColumn());
        selection.add(9, 0);

        assertEquals(List.of(new CellRange(3, 0, 5, 9), new CellRange(9, 0, 9, 9)), selection.getRanges());
    }

    @Test
    void columnModeSelectsWholeColumns() {
        selection.setMode(SelectionMode.COLUMN);
        selection.select(3, 4);
        selection.extend(50, 6);

        assertEquals(List.of(new CellRange(0, 4, 99, 6)), selection.getRanges());
    }

    @Test
    void rowHeaderSelectsRowsInAreaModeAndExtendKeepsRows() {
        selection.select(2, 3);
        selection.selectRow(10, false);
        selection.extendRows(12);
        selection.selectRow(20, true);

        assertEquals(List.of(new CellRange(10, 0, 12, 9), new CellRange(20, 0, 20, 9)), selection.getRanges());
        assertEquals(3, selection.getFocusedColumn(), "row selection keeps the focus column");
    }

    @Test
    void rowHeaderOnlyMovesFocusInColumnMode() {
        selection.setMode(SelectionMode.COLUMN);
        selection.select(2, 3);
        selection.selectRow(10, false);

        assertEquals(List.of(new CellRange(0, 3, 99, 3)), selection.getRanges());
        assertEquals(10, selection.getFocusedRow());
    }

    @Test
    void changingModeClears() {
        selection.select(1, 1);
        selection.setMode(SelectionMode.ROW);

        assertTrue(selection.isEmpty());
    }

    @Test
    void insertedRowsPushSelectionDownAndGrowSpanningRanges() {
        selection.select(10, 1);
        selection.extend(12, 2);
        selection.add(50, 5);
        selection.rowsInserted(11, 3);

        assertEquals(List.of(new CellRange(10, 1, 15, 2), new CellRange(53, 5, 53, 5)), selection.getRanges());
        assertEquals(53, selection.getFocusedRow());
    }

    @Test
    void deletedRowsShrinkOrRemoveRanges() {
        selection.select(10, 0);
        selection.extend(20, 0);
        selection.add(30, 0);
        selection.add(5, 0);
        selection.rowsDeleted(15, 16);

        assertEquals(List.of(new CellRange(10, 0, 14, 0), new CellRange(5, 0, 5, 0)), selection.getRanges());
        assertEquals(5, selection.getFocusedRow());
    }

    @Test
    void deletedFocusMovesToTheRowTakingItsPlace() {
        selection.select(40, 3);
        selection.rowsDeleted(38, 5);

        assertTrue(selection.isEmpty());
        assertEquals(38, selection.getFocusedRow());
    }

    @Test
    void columnChangesKeepOnlyWholeRows() {
        selection.selectRow(4, false);
        selection.add(7, 7);
        size(100, 8);
        selection.keepWholeRows();

        assertEquals(List.of(new CellRange(4, 0, 4, 7)), selection.getRanges());
        assertEquals(7, selection.getFocusedColumn());
    }

    @Test
    void clipToRowCountKeepsWhatStillFits() {
        selection.select(5, 1);
        selection.extend(80, 1);
        size(50, 10);
        selection.clipToRowCount();

        assertEquals(List.of(new CellRange(5, 1, 49, 1)), selection.getRanges());
        assertEquals(49, selection.getFocusedRow());
    }
}
