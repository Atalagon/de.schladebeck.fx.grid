package de.schladebeck.fx.grid.showcase.examples;

import javafx.scene.Node;

/** One tab of the showcase. The description is shown above the example, its source file below it. */
public abstract class Example {

    private final String title;
    private final String description;

    protected Example(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public final String title() {
        return title;
    }

    public final String description() {
        return description;
    }

    public abstract Node create();

    /** Stops background work when the showcase switches to another example. */
    public void stop() {
    }
}
