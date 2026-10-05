package de.schladebeck.fx.grid.model;

/** Notified when a {@link GridModel} changes. */
@FunctionalInterface
public interface GridModelListener {

    /** Called on the JavaFX application thread after the model changed. */
    void gridChanged(GridModelEvent event);
}
