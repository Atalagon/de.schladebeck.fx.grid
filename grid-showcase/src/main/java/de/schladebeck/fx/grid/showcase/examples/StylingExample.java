package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ListGridModel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;

public final class StylingExample extends Example {

    private record Stylesheet(String name, String css) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Stylesheet> STYLESHEETS = List.of(
            new Stylesheet("Modena, the default", """
                    /* No rules: the grid takes its colors from the Modena theme. */
                    """),
            new Stylesheet("Dark, from four theme variables", """
                    .styling-example {
                        -fx-base: #3c3f41;
                        -fx-control-inner-background: #1e1f22;
                        -fx-accent: #2675bf;
                        -fx-focus-color: #3574f0;
                        -fx-background-color: #2b2b2b;
                    }
                    """),
            new Stylesheet("Larger font", """
                    .styling-example {
                        -fx-font-size: 16px;
                    }
                    """),
            new Stylesheet("Own look, from -grid-* properties", """
                    .grid {
                        -grid-cell-background: #0d1117;
                        -grid-alternate-cell-background: #161b22;
                        -grid-text-fill: #c9d1d9;
                        -grid-line-color: #21262d;
                        -grid-header-background: #161b22;
                        -grid-header-text-fill: #f0f6fc;
                        -grid-selection-background: #1f3a5f;
                        -grid-focus-color: #58a6ff;
                        -grid-font-family: "Consolas";
                        -grid-row-height: 28;
                        -fx-background-color: #30363d, #0d1117;
                        -fx-base: #21262d; /* the scroll bars */
                    }
                    """),
            new Stylesheet("Selection color depending on focus", """
                    .grid {
                        -grid-selection-background: #d3d3d3;
                    }
                    .grid:focused {
                        -grid-selection-background: derive(-fx-accent, 70%);
                    }
                    """));

    public StylingExample() {
        super("CSS styling", "Colors, fonts and sizes of the grid are CSS properties, and their defaults come from the "
                + "Modena theme and the inherited font. Pick a stylesheet: theme variables or a larger font change the "
                + "grid along with everything around it, -grid-* properties give it a look of its own. Edit the "
                + "stylesheet on the right, it applies as you type.");
    }

    @Override
    public Node create() {
        Grid grid = new Grid(ListGridModel.ofRecords(Person.class, SampleData.people(10_000)));
        grid.getSelectionModel().select(2, 1);
        grid.getSelectionModel().extend(5, 3);

        TextArea css = new TextArea();
        css.setPrefColumnCount(36);
        css.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
        ChoiceBox<Stylesheet> choice = new ChoiceBox<>(FXCollections.observableList(STYLESHEETS));
        BorderPane example = new BorderPane(grid, new ToolBar(new Label("Stylesheet"), choice), css, null, null);
        example.getStyleClass().add("styling-example");

        css.textProperty().addListener((o, old, text) -> example.getStylesheets().setAll(
                "data:text/css;base64," + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))));
        choice.valueProperty().addListener((o, old, stylesheet) -> css.setText(stylesheet.css()));
        choice.setValue(STYLESHEETS.get(1));
        return example;
    }
}
