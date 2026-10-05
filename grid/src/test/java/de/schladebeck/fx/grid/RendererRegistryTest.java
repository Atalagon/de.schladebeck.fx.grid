package de.schladebeck.fx.grid;

import static org.junit.jupiter.api.Assertions.assertSame;

import de.schladebeck.fx.grid.Fx.ArrayModel;
import de.schladebeck.fx.grid.model.ComputedGridModel;
import de.schladebeck.fx.grid.model.GridModel;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRenderers;
import org.junit.jupiter.api.Test;

class RendererRegistryTest {

    private final RendererRegistry registry = new RendererRegistry();

    @Test
    void mixedColumnsPickByValue() {
        // column 0 holds a different type in every row, as in a property sheet
        ArrayModel model = new ArrayModel(new Object[][] {{"text"}, {42}, {true}, {3.5}});
        registry.resize(1);

        assertSame(CellRenderers.TEXT, registry.get(model, 0, "text"));
        assertSame(CellRenderers.NUMBER, registry.get(model, 0, 42));
        assertSame(CellRenderers.BOOLEAN, registry.get(model, 0, true));
        assertSame(CellRenderers.NUMBER, registry.get(model, 0, 3.5));
        assertSame(CellRenderers.TEXT, registry.get(model, 0, null));
    }

    @Test
    void columnRendererAndColumnClassWin() {
        // Built on the test thread: setup applies right away while no grid listens.
        GridModel model = new ComputedGridModel(1, 2, (row, column) -> "x").columnTypes(column -> Number.class);
        registry.resize(2);
        CellRenderer custom = (gc, cell) -> {
        };
        registry.setColumn(1, custom);

        assertSame(CellRenderers.NUMBER, registry.get(model, 0, "not a number"), "declared column class");
        assertSame(custom, registry.get(model, 1, "x"));
    }

    @Test
    void defaultsForOwnTypes() {
        ArrayModel model = new ArrayModel(new Object[][] {{1}});
        registry.resize(1);
        CellRenderer integers = (gc, cell) -> {
        };
        registry.setDefault(Integer.class, integers);

        assertSame(integers, registry.get(model, 0, 7));
        assertSame(CellRenderers.NUMBER, registry.get(model, 0, 7L));
    }

    @Test
    void interfacesAndPrimitiveColumns() {
        ArrayModel model = new ArrayModel(new Object[][] {{"a"}});
        registry.resize(2);
        CellRenderer sequences = (gc, cell) -> {
        };
        registry.setDefault(CharSequence.class, sequences);
        assertSame(sequences, registry.get(model, 0, "text"), "String implements CharSequence");
        assertSame(sequences, registry.get(model, 0, new StringBuilder("b")));
        assertSame(CellRenderers.NUMBER, registry.get(model, 0, 1), "a class beats an interface");

        GridModel doubles = new ComputedGridModel(1, 1, (row, column) -> 1.5).columnTypes(column -> double.class);
        assertSame(CellRenderers.NUMBER, registry.get(doubles, 0, 1.5), "double.class is a number column");
    }
}
