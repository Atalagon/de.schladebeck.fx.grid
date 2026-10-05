/// FX Grid: a JavaFX table control that paints all cells into one canvas. See [de.schladebeck.fx.grid].
module de.schladebeck.fx.grid {
    requires transitive javafx.controls;

    exports de.schladebeck.fx.grid;
    exports de.schladebeck.fx.grid.model;
    exports de.schladebeck.fx.grid.render;
}
