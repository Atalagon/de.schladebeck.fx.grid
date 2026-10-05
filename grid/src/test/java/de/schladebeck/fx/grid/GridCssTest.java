package de.schladebeck.fx.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.model.ComputedGridModel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javafx.css.CssMetaData;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The defaults in grid.css follow the theme and the inherited font, and stronger values still win. */
class GridCssTest {

    private static final String DARK = ".root { -fx-base: #3c3f41; -fx-control-inner-background: #1e1f22;"
            + " -fx-focus-color: #3574f0; }";

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    /// An installed font family other than the default that has an italic face, so the tests do not depend on a
    /// particular font being there.
    private static final String FAMILY = Font.getFamilies().stream()
            .filter(family -> !family.equals(Font.getDefault().getFamily()))
            .filter(family -> Font.font(family, FontPosture.ITALIC, 12).getStyle().toLowerCase().contains("italic"))
            .findFirst().orElse(Font.getDefault().getFamily());

    /** A grid inside a parent, so it inherits from `.root` like in an application. */
    private static Grid styled(String css) {
        Grid grid = new Grid(new ComputedGridModel(10, 3, (row, column) -> row));
        Scene scene = new Scene(new StackPane(grid), 400, 300);
        scene.getStylesheets().add("data:text/css;base64,"
                + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8)));
        scene.getRoot().applyCss();
        return grid;
    }

    @Test
    void theClassListsEveryGridProperty() {
        List<String> names = Grid.getClassCssMetaData().stream().map(CssMetaData::getProperty)
                .filter(name -> name.startsWith("-grid-")).toList();
        assertEquals(List.of("-grid-font", "-grid-header-font", "-grid-text-fill", "-grid-cell-background",
                "-grid-alternate-cell-background", "-grid-alternate-column-background",
                "-grid-line-color", "-grid-selection-background",
                "-grid-focus-color", "-grid-header-background", "-grid-header-text-fill", "-grid-row-height",
                "-grid-header-height", "-grid-cell-padding"), names);
    }

    @Test
    void defaultsMatchTheInitialValues() throws Exception {
        Fx.run(() -> {
            Grid fresh = new Grid();
            Grid styled = styled("");

            assertEquals(hex(fresh.getTextFill()), hex(styled.getTextFill()));
            assertEquals(hex(fresh.getCellBackground()), hex(styled.getCellBackground()));
            assertEquals(hex(fresh.getAlternateCellBackground()), hex(styled.getAlternateCellBackground()));
            assertEquals(hex(fresh.getGridLineColor()), hex(styled.getGridLineColor()));
            assertEquals(hex(fresh.getSelectionBackground()), hex(styled.getSelectionBackground()));
            assertEquals(hex(fresh.getFocusColor()), hex(styled.getFocusColor()));
            assertEquals(hex(fresh.getHeaderBackground()), hex(styled.getHeaderBackground()));
            assertEquals(hex(fresh.getHeaderTextFill()), hex(styled.getHeaderTextFill()));
            assertEquals(fresh.getFont(), styled.getFont());
            assertEquals(fresh.getHeaderFont(), styled.getHeaderFont());
            assertEquals(fresh.getRowHeight(), styled.getRowHeight(), 1e-4);
            assertEquals(fresh.getHeaderHeight(), styled.getHeaderHeight(), 1e-4);
            assertEquals(fresh.getCellPadding(), styled.getCellPadding(), 1e-4);
        });
    }

    @Test
    void colorsFollowTheTheme() throws Exception {
        Fx.run(() -> {
            Grid grid = styled(DARK);

            assertEquals(Color.web("#1e1f22"), grid.getCellBackground());
            assertEquals(Color.web("#3c3f41"), grid.getHeaderBackground());
            assertEquals(Color.web("#3574f0"), grid.getFocusColor());
            assertTrue(grid.getTextFill().getBrightness() > 0.9, "light text on dark cells");
            assertTrue(grid.getHeaderTextFill().getBrightness() > 0.9, "light text on a dark header");
            assertTrue(grid.getSelectionBackground().getBrightness() < 0.5, "a dark selection under light text");
            assertTrue(grid.getGridLineColor().getBrightness() > grid.getCellBackground().getBrightness(),
                    "lines lighter than dark cells");
        });
    }

    @Test
    void fontsAndSizesFollowTheInheritedFont() throws Exception {
        Fx.run(() -> {
            Grid grid = styled(".root { -fx-font: 20px \"" + FAMILY + "\"; }");

            assertEquals(FAMILY, grid.getFont().getFamily());
            assertEquals(20, grid.getFont().getSize());
            assertEquals(20, grid.getHeaderFont().getSize());
            assertEquals(FontWeight.BOLD, FontWeight.findByName(grid.getHeaderFont().getStyle().split(" ")[0]));
            assertEquals(40, grid.getRowHeight(), 1e-4);
            assertEquals(2.333333 * 20, grid.getHeaderHeight(), 0.05, "JavaFX rounds em sizes to 0.1");
            assertEquals(10, grid.getCellPadding(), 1e-4);
        });
    }

    @Test
    void fontPartsKeepTheRestOfTheInheritedFont() throws Exception {
        Fx.run(() -> {
            Grid grid = styled(".root { -fx-font: 20px \"" + FAMILY + "\"; } .grid { -grid-font-style: italic; }");

            assertEquals(FAMILY, grid.getFont().getFamily());
            assertEquals(20, grid.getFont().getSize());
            assertTrue(grid.getFont().getStyle().toLowerCase().contains("italic"), grid.getFont().getStyle());
        });
    }

    @Test
    void strongerValuesWin() throws Exception {
        Fx.run(() -> {
            Grid grid = new Grid(new ComputedGridModel(10, 3, (row, column) -> row));
            grid.setCellBackground(Color.RED);
            grid.setRowHeight(30);
            Scene scene = new Scene(new StackPane(grid));
            scene.getRoot().applyCss();
            assertEquals(Color.RED, grid.getCellBackground(), "code beats the defaults");
            assertEquals(30, grid.getRowHeight());

            Grid shorthand = styled(".grid { -grid-header-font: 14px \"" + FAMILY + "\"; }");
            assertEquals("Regular", shorthand.getHeaderFont().getStyle(), "a stylesheet beats the default weight");

            Grid inline = styled(".grid { -grid-row-height: 30; }");
            inline.setStyle("-grid-row-height: 40;");
            inline.applyCss();
            assertEquals(40, inline.getRowHeight(), "an inline style beats the stylesheet");
        });
    }

    @Test
    void fittedColumnsFollowAFontChange() throws Exception {
        Fx.run(() -> {
            Grid grid = Fx.show(new Grid(new ComputedGridModel(10, 3, (row, column) -> "value " + row)));
            grid.getColumn(2).setWidth(50);
            double fitted = grid.getColumn(0).getWidth();

            grid.setFont(Font.font(grid.getFont().getFamily(), 24));
            Fx.layout(grid);

            assertTrue(grid.getColumn(0).getWidth() > 1.5 * fitted, "fitted again for the larger font");
            assertEquals(50, grid.getColumn(2).getWidth(), "a width set in code stays");
        });
    }

    @Test
    void stripingCanBeTurnedOff() throws Exception {
        Fx.run(() -> assertNull(
                styled(".grid { -grid-alternate-cell-background: null; }").getAlternateCellBackground()));
    }

    /** Derived colors keep more precision than a hex value; compare what ends up on screen. */
    private static String hex(Color color) {
        return color.toString().substring(0, 8);
    }
}
