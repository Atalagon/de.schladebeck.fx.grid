package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.TextMetrics;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.text.Font;

public final class MultiLineExample extends Example {

    private static final int NOTES = 1;
    private static final int MAX_LINES = 4;

    public MultiLineExample() {
        super("Multi-line text", "CellRenderers.WRAPPED_TEXT wraps text over the height of its row. A "
                + "RowHeightProvider gives each row its own height, here as many lines as the notes need, measured "
                + "with TextMetrics. The maximum row height keeps rows at four lines or less. Resize the notes column "
                + "and the rows adapt. Only rows with a height other than the default are stored, so this scales to "
                + "millions of rows.");
    }

    @Override
    public Node create() {
        ListGridModel<Person> people = new ListGridModel<Person>()
                .column("Name", Person::name)
                .column("Notes", SampleData::longNotes)
                .column("City", Person::city);
        people.setAll(SampleData.people(10_000));

        Grid grid = new Grid(people);
        grid.getStyleClass().add("modern"); // styles.css
        grid.getColumn(NOTES).setWidth(360);
        grid.getColumn(NOTES).setRenderer(CellRenderers.WRAPPED_TEXT);
        // The font is read where it is used: CSS sets it when the grid is shown, and a theme may change it later.
        grid.maxRowHeightProperty().bind(Bindings.createDoubleBinding(
                () -> MAX_LINES * TextMetrics.lineHeight(grid.getFont()) + 8, grid.fontProperty()));
        grid.setRowHeightProvider(row -> {
            Font font = grid.getFont();
            double width = grid.getColumn(NOTES).getWidth() - 2 * grid.getCellPadding();
            int lines = TextMetrics.lineCount(SampleData.longNotes(people.getItem(row)), font, width);
            return lines * TextMetrics.lineHeight(font) + 8;
        });
        return grid;
    }
}
