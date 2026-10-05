package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.AbstractGridModel;
import de.schladebeck.fx.grid.model.GridModel;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRenderers;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import javafx.scene.Node;

public final class ObjectsAsColumnsExample extends Example {

    public record Laptop(String name, double price, double weight, int batteryHours, boolean touch,
                         LocalDate released) {
    }

    public ObjectsAsColumnsExample() {
        super("Objects as columns", "The grid is not tied to one object per row. This GridModel shows one laptop per "
                + "column and one property per row, with the property names as row names, in about twenty lines. Every "
                + "row holds a different type, so renderers are picked per cell: numbers right aligned, booleans as "
                + "check boxes. A CellRendererProvider formats the price and weight rows.");
    }

    @Override
    public Node create() {
        List<Laptop> laptops = List.of(
                new Laptop("Aero 14", 1_499, 1.29, 14, false, LocalDate.of(2026, 3, 12)),
                new Laptop("Book Pro 16", 2_799, 2.14, 22, false, LocalDate.of(2025, 11, 4)),
                new Laptop("Flex 13", 999, 1.35, 11, true, LocalDate.of(2026, 1, 20)),
                new Laptop("Studio X", 3_299, 1.92, 9, true, LocalDate.of(2026, 6, 2)),
                new Laptop("Travel Air", 1_199, 0.98, 16, false, LocalDate.of(2025, 9, 15)));
        List<String> properties = List.of("Price", "Weight", "Battery hours", "Touch screen", "Released");
        List<Function<Laptop, Object>> values = List.of(
                Laptop::price, Laptop::weight, Laptop::batteryHours, Laptop::touch, Laptop::released);

        GridModel model = new AbstractGridModel() {
            @Override
            public int getRowCount() {
                return properties.size();
            }

            @Override
            public int getColumnCount() {
                return laptops.size();
            }

            @Override
            public Object getValueAt(int row, int column) {
                return values.get(row).apply(laptops.get(column));
            }

            @Override
            public String getRowName(int row) {
                return properties.get(row);
            }

            @Override
            public String getColumnName(int column) {
                return laptops.get(column).name();
            }
        };

        Grid grid = new Grid(model);
        grid.getStyleClass().add("modern"); // styles.css
        grid.setRowHeaderVisible(true);
        grid.setSortable(false);
        CellRenderer euros = CellRenderers.number("#,##0 €");
        CellRenderer kilos = CellRenderers.number("0.00 kg");
        grid.setCellRendererProvider((row, column, value) -> switch (row) {
            case 0 -> euros;
            case 1 -> kilos;
            default -> null;
        });
        return grid;
    }
}
