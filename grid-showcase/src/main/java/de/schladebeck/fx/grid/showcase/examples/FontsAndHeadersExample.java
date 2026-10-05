package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.CellStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.geometry.HPos;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

public final class FontsAndHeadersExample extends Example {

    public enum Kind { ACCOUNT, SUBTOTAL, RESULT }

    public record Line(String account, String name, double q1, double q2, double q3, double q4, String note,
                       Kind kind) {

        double year() {
            return q1 + q2 + q3 + q4;
        }

        /** A subtotal or result line summing up `lines`. */
        static Line sum(String name, Kind kind, List<Line> lines) {
            return new Line("", name, sum(lines, Line::q1), sum(lines, Line::q2), sum(lines, Line::q3),
                    sum(lines, Line::q4), "", kind);
        }

        private static double sum(List<Line> lines, ToDoubleFunction<Line> quarter) {
            return lines.stream().mapToDouble(quarter).sum();
        }
    }

    private static final int ACCOUNT = 0;
    private static final int Q1 = 2;
    private static final int YEAR = 6;
    private static final int NOTE = 7;
    private static final String[] MONTHS = {"January to March", "April to June", "July to September",
            "October to December"};

    public FontsAndHeadersExample() {
        super("Fonts and headers", "Cell styles set fonts per cell: account codes in a monospaced font, subtotals in "
                + "bold, the result larger, notes in italics. withFontWeight and withFontPosture keep the grid's font "
                + "size, so they follow the theme. Headers are styled per column: the quarters have their own renderer "
                + "with two lines, the second one in a smaller font and fitted with fitText(text, font), so the "
                + "columns are wide enough for it. The year has a dark header style, the notes an italic one. Click a "
                + "header to sort: the arrow stays visible on every header.");
    }

    @Override
    public Node create() {
        ListGridModel<Line> report = new ListGridModel<Line>()
                .column("Account", Line::account)
                .column("Name", Line::name)
                .column("Q1", Line::q1)
                .column("Q2", Line::q2)
                .column("Q3", Line::q3)
                .column("Q4", Line::q4)
                .column("Year", Line::year)
                .column("Note", Line::note);
        report.setAll(lines());

        Grid grid = new Grid(report);
        for (int column = Q1; column <= YEAR; column++) {
            grid.getColumn(column).setRenderer(CellRenderers.number("#,##0"));
        }

        // Whole fonts of their own follow the grid's font size, which CSS sets once the grid is shown. A binding
        // keeps one style instance per font, so the grid can cache its fonts.
        ObjectBinding<CellStyle> code = Bindings.createObjectBinding(
                () -> CellStyle.DEFAULT.withFont(Font.font("Monospaced", grid.getFont().getSize())),
                grid.fontProperty());
        ObjectBinding<CellStyle> result = Bindings.createObjectBinding(() -> CellStyle.DEFAULT
                .withFont(Font.font(grid.getFont().getFamily(), FontWeight.BOLD, grid.getFont().getSize() + 3))
                .withBackground(Color.web("#dbeafe")), grid.fontProperty());
        CellStyle negative = CellStyle.DEFAULT.withTextFill(Color.web("#cf222e"));
        CellStyle subtotal = CellStyle.DEFAULT.withFontWeight(FontWeight.BOLD).withBackground(Color.web("#eef2f7"));
        CellStyle negativeSubtotal = subtotal.withTextFill(Color.web("#cf222e"));
        CellStyle note = CellStyle.DEFAULT.withFontPosture(FontPosture.ITALIC).withTextFill(Color.web("#6e7781"));
        grid.setCellStyleProvider((row, column, value) -> {
            Kind kind = report.getItem(row).kind();
            boolean below = value instanceof Double d && d < 0;
            return switch (kind) {
                case RESULT -> result.get();
                case SUBTOTAL -> below ? negativeSubtotal : subtotal;
                case ACCOUNT -> column == ACCOUNT ? code.get() : column == NOTE ? note : below ? negative : null;
            };
        });
        grid.setRowHeightProvider(row -> report.getItem(row).kind() == Kind.RESULT ? 1.5 * grid.getRowHeight() : 0);

        grid.setStyle("-grid-header-height: 4em;"); // two lines, in em so it grows with the font
        for (int column = Q1; column < YEAR; column++) {
            grid.getColumn(column).setHeaderRenderer(QUARTER_HEADER);
        }
        grid.getColumn(YEAR).setHeaderStyle(CellStyle.DEFAULT.withBackground(Color.web("#24292f"))
                .withTextFill(Color.WHITE).withAlignment(HPos.RIGHT));
        grid.getColumn(NOTE).setHeaderStyle(CellStyle.DEFAULT.withFontPosture(FontPosture.ITALIC));
        return grid;
    }

    /// The quarter in the header font, and its months below in a smaller one, both right aligned. The canvas
    /// comes with the header's font and text fill already set.
    private static final CellRenderer QUARTER_HEADER = (gc, cell) -> {
        Font small = Font.font(cell.getFont().getFamily(), cell.getFont().getSize() - 2);
        double right = cell.getX() + cell.getWidth() - cell.getPadding();
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(cell.fitText((String) cell.getValue()), right, cell.getY() + cell.getHeight() * 0.32);
        gc.setFont(small);
        gc.fillText(cell.fitText(MONTHS[cell.getModelColumn() - Q1], small), right,
                cell.getY() + cell.getHeight() * 0.7);
    };

    private static List<Line> lines() {
        List<Line> revenue = List.of(
                new Line("4000", "Product sales", 412_000, 398_500, 455_200, 501_300, "Two new customers in Q4",
                        Kind.ACCOUNT),
                new Line("4100", "Services", 120_400, 131_900, 128_300, 140_800, "", Kind.ACCOUNT),
                new Line("4200", "Licenses", 56_000, 54_200, 61_000, 75_500, "Price increase in July", Kind.ACCOUNT));
        List<Line> costs = List.of(
                new Line("6000", "Salaries", -310_000, -312_500, -318_000, -325_400, "Three hires in Q3", Kind.ACCOUNT),
                new Line("6100", "Rent", -42_000, -42_000, -42_000, -42_000, "", Kind.ACCOUNT),
                new Line("6200", "Travel", -18_300, -22_100, -15_900, -27_400, "Offsite in Q4", Kind.ACCOUNT),
                new Line("6300", "Marketing", -35_000, -61_000, -28_000, -44_000, "Launch campaign in Q2",
                        Kind.ACCOUNT));
        Line totalRevenue = Line.sum("Total revenue", Kind.SUBTOTAL, revenue);
        Line totalCosts = Line.sum("Total costs", Kind.SUBTOTAL, costs);
        List<Line> lines = new ArrayList<>(revenue);
        lines.add(totalRevenue);
        lines.addAll(costs);
        lines.add(totalCosts);
        lines.add(Line.sum("Operating result", Kind.RESULT, List.of(totalRevenue, totalCosts)));
        return lines;
    }
}
