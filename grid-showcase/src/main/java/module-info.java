module de.schladebeck.fx.grid.showcase {
    requires de.schladebeck.fx.grid;

    exports de.schladebeck.fx.grid.showcase to javafx.graphics;
    // ListGridModel.ofRecords reads the example records reflectively.
    exports de.schladebeck.fx.grid.showcase.examples to de.schladebeck.fx.grid;
}
