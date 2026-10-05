/// Where a [Grid][de.schladebeck.fx.grid.Grid]'s data comes from. A [GridModel] is a rectangle of values
/// addressed by row and column; the grid asks it only for the cells it shows, so the data can live anywhere.
///
/// ## Ready-made models
///
/// | Model | Data | Size |
/// |---|---|---|
/// | [ListGridModel] | a list of objects, one per row, a column per getter or record component | what fits in memory |
/// | [ComputedGridModel] | a function of row and column | any, no memory |
/// | [PagedGridModel] | pages loaded on demand in the background, e.g. from a database | any, a few pages in memory |
/// | [TreeGridModel] | a tree of objects; the children of an expanded one follow it | the opened part of the tree |
///
/// [ListGridModel#of] shows a JavaFX `ObservableList` and follows every change of it, including changes inside items
/// that the list reports through an extractor.
///
/// ## Writing a model
///
/// Anything else is a small subclass of [AbstractGridModel], for example one object per column instead of per row:
///
/// ```java
/// GridModel laptops = new AbstractGridModel() {
///     public int getRowCount() { return properties.size(); }
///     public int getColumnCount() { return items.size(); }
///     public Object getValueAt(int row, int column) { return properties.get(row).apply(items.get(column)); }
///     public String getRowName(int row) { return propertyNames.get(row); }
/// };
/// ```
///
/// Rows and columns are symmetric: both have names, and each cell may hold a value of any type. A model that can
/// sort better than the grid, such as a database query, overrides [GridModel#sortRows]: it gets the [SortKey]s,
/// one per sort column.
///
/// ## Threads
///
/// The grid reads models on the JavaFX application thread. Changes from other threads go through
/// [AbstractGridModel#applyChange]: it runs the change right away on the JavaFX thread, and otherwise queues it.
/// Queued changes run on the JavaFX thread in order, in batches of about 8 ms. Before the toolkit starts, changes
/// run right away on the calling thread. The changing methods and the builders of the ready-made models do this
/// already; builders of your own use [AbstractGridModel#applySetup], so a model can be set up on any thread before
/// a grid shows it. The grid rejects events fired from another thread. An `ObservableList` shown with
/// [ListGridModel#of] changes on the JavaFX thread only, like any list shown in a JavaFX control.
///
/// ## Events
///
/// Models report changes with [GridModelEvent]s: rows inserted, deleted or updated, all rows changed (`DATA`), or
/// everything including the columns changed (`STRUCTURE`). The grid repaints only if a changed row is on screen,
/// keeps the visible rows in place when rows are inserted or deleted above them, and moves the selection with its
/// rows.
///
/// ## Implementation notes
///
/// [ListGridModel], [PagedGridModel] and [TreeGridModel] share the column logic (names, getters, update functions,
/// row names) through the package-private `ItemColumns`. The items stay with each model.
///
/// A [ListGridModel] showing an `ObservableList` reads a copy of it. A list change can come in several parts (as
/// from `removeAll`); each part goes into the copy right before its event, so every event matches the rows the grid
/// reads. The list holds the model through a weak listener, so a list that lives on does not keep grids alive.
///
/// [PagedGridModel] requests a page when a cell of it is asked for and not loaded. Requests are served newest
/// first, a few at a time, so fast scrolling loads what is on screen now. Loaded pages arrive through
/// `applyChange` and fire an update for their rows, which repaints only if they are on screen. A reload or a new
/// sort order starts a new generation; pages still loading for an older one are dropped when they arrive.
package de.schladebeck.fx.grid.model;

