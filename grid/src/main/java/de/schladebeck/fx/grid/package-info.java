/// A JavaFX table control for tables of any size. [Grid] paints all cells into one canvas, so its node count
/// stays the same for ten cells or 100 billion, and only the visible cells cost time.
///
/// ## Getting started
///
/// ```java
/// ListGridModel<Person> people = new ListGridModel<Person>()
///         .column("Name", Person::name)
///         .column("Salary", Person::salary);
/// people.setAll(loadPeople());          // from any thread
///
/// Grid grid = new Grid(people);
/// grid.getColumn(1).setRenderer(CellRenderers.number("#,##0.00")).setMinWidth(90);
/// grid.setCellContextMenuFactory(cell -> new ContextMenu(new MenuItem("Open " + cell.value())));
/// ```
///
/// The data comes from any [de.schladebeck.fx.grid.model.GridModel]; [de.schladebeck.fx.grid.model] has
/// ready-made ones for lists, functions, paged sources and trees. A [TreeGrid] shows a tree.
///
/// ## Rows and columns
///
/// Rows and columns each have a view order (as shown) and a model order (as in the data). Rows are filtered
/// ([Grid#setRowFilter]) and sorted by one or more columns ([Grid#sort(java.util.List)], Shift+click on a header);
/// columns are hidden and moved ([GridColumn#setVisible], [Grid#moveColumn]). Positions such as the selection and
/// [Grid#cellAt] use view indices; configuration and data use model indices. [Grid#viewToModelRow] and its
/// relatives convert.
///
/// A [GridColumn] holds a column's width and limits, visibility, renderer, header style and renderer,
/// [comparator][GridColumn#setComparator], [converter][GridColumn#setConverter] and a
/// [prototype value][GridColumn#setPrototypeValue] for fitting the width before data arrives.
/// [Grid#getColumn(String)] finds a column by name; [Grid#columnGroupsProperty()] adds rows of group names above
/// the headers. Rows have the row height, or their own from a [RowHeightProvider], within
/// [Grid#minRowHeightProperty()] and [Grid#maxRowHeightProperty()].
///
/// The selection and the focused cell stay with their rows when the view is sorted or filtered and when rows are
/// inserted or deleted; rows inserted or deleted above the viewport do not move what is shown. Sorting or
/// filtering keeps a selection of up to 100,000 rows (whole columns do not count) and clears a larger one; so does
/// inserting or deleting more than 1,000 rows at once in a sorted or filtered view.
///
/// ## Painting
///
/// The painting API is in [de.schladebeck.fx.grid.render]. [CellRenderer]s paint cells straight onto the canvas.
/// The grid picks one per cell: a [CellRendererProvider] if set, else the column's renderer, else one for the
/// column type or the value's type. [CellRenderers] has the built-in ones and helpers for formatted and wrapped
/// text. A [CellStyleProvider] styles single cells: background, text color, font, bold or italic, alignment.
///
/// Column headers are painted the same way, by [Grid#headerRendererProperty()] or a column's own
/// [GridColumn#setHeaderRenderer], styled by [GridColumn#setHeaderStyle]. The grid keeps room for the sort arrow
/// and paints it after the renderer.
///
/// Renderers start from a reset canvas state (see [CellRenderer]). A renderer that throws leaves its cell empty;
/// the grid logs the first exception of each kind and keeps painting.
///
/// ## CSS reference
///
/// Colors, fonts and sizes are CSS properties of the `.grid` style class. Their defaults come from the Modena
/// theme variables and the inherited font, so a dark theme or a larger font set on `.root` reaches the grid
/// without any `-grid-*` property:
///
/// ```css
/// .root {
///     -fx-base: #3c3f41;
///     -fx-control-inner-background: #1e1f22;
///     -fx-accent: #2675bf;
///     -fx-font-size: 14px;
/// }
/// ```
///
/// | Property | Type | Default | Paints |
/// |---|---|---|---|
/// | `-grid-font` | font | the inherited `-fx-font` | cell text |
/// | `-grid-header-font` | font | the inherited `-fx-font`, bold | header text |
/// | `-grid-text-fill` | color | `-fx-text-inner-color` | cell text, check boxes |
/// | `-grid-cell-background` | color | `-fx-control-inner-background` | cells |
/// | `-grid-alternate-cell-background` | color | `-fx-control-inner-background-alt` | every other row |
/// | `-grid-alternate-column-background` | color | none | laid over every other column |
/// | `-grid-line-color` | color | the cell background, a shade darker or lighter | grid lines |
/// | `-grid-selection-background` | color | a neutral gray, a tint of `-fx-accent` while focused | selected cells |
/// | `-grid-focus-color` | color | `-fx-focus-color` | focused cell, selection outline, drop marker |
/// | `-grid-header-background` | color | `-fx-base` | row and column header |
/// | `-grid-header-text-fill` | color | `-fx-text-base-color` | header text, sort arrow |
/// | `-grid-row-height` | size | `2em` (24 at font size 12) | rows without their own height |
/// | `-grid-header-height` | size | `2.333333em` (28) | column header |
/// | `-grid-cell-padding` | size | `0.5em` (6) | space left and right of the text in a cell |
///
/// `null` as `-grid-alternate-cell-background` turns striping off; a see-through
/// `-grid-alternate-column-background` such as `rgba(0, 0, 0, 0.04)` combines with the row stripes. Grid lines are
/// darker than the cells on light themes and lighter on dark ones; the lines after the frozen columns and rows and
/// the header lines are a shade stronger.
/// The focused selection background is light on light themes and dark on dark ones, and is mixed into the headers
/// of selected rows and columns. The focused cell's border and the selection outline are painted only while the
/// grid has the keyboard focus. The defaults are written down in `grid.css` next to this package; each property
/// also has a Java property of the same name, such as [Grid#cellBackgroundProperty()].
///
/// Values:
///
/// - Colors may use theme variables, `derive` and `ladder`, for example
///   `-grid-selection-background: derive(-fx-accent, 70%);`, or variables of your own defined on `.root`.
/// - Fonts take the shorthand (`-grid-font: 14px "Segoe UI";`) or single parts (`-grid-font-size`,
///   `-grid-font-family`, `-grid-font-weight`, `-grid-font-style`); missing parts come from the inherited font.
/// - Sizes in `em` are relative to the grid's font size (`-fx-font-size`). Setting `-fx-font-size` on `.grid` or
///   `.root` grows the cell font, the header font, the row height and the padding together; a size given only in
///   `-grid-font` changes the text, not the row height.
///
/// Which value wins, strongest first: an inline style ([Grid#setStyle]), a stylesheet of the scene or a parent, a
/// value set in code (such as [Grid#setRowHeight]), the defaults.
///
/// Selectors:
///
/// | Selector | Matches |
/// |---|---|
/// | `.grid` | the grid; its frame is a normal `-fx-background-color` with insets |
/// | `.grid:focused` | the grid while it has the keyboard focus; by default it sets the accent-colored selection |
/// | `.grid .scroll-bar` | the two scroll bars, standard JavaFX scroll bars |
/// | `.grid > .grid-editor` | the text field of in-place editing |
/// | `.grid > .grid-editor:invalid` | the text field while its text cannot be converted to the cell's type |
///
/// ```css
/// .grid { -grid-selection-background: #e0e0e0; }
/// .grid:focused { -grid-selection-background: derive(-fx-accent, 70%); }
/// ```
///
/// CSS is applied before the first layout pass, so getters such as [Grid#getFont()] return the defaults until the
/// grid is shown. Read them inside renderers, providers and listeners, and give font-dependent sizes in `em`.
///
/// Cells, rows and columns are not nodes, so CSS cannot style them one by one: use a [CellStyleProvider] for
/// cells, [GridColumn#setHeaderStyle] for headers, or a [CellRenderer]. Context menus and tooltips are standard
/// JavaFX popups with their usual style classes.
///
/// ## Selection, menus and editing
///
/// [GridSelectionModel] holds ranges of cells in one of four [SelectionMode]s. [Grid#getSelectedModelRows()] and
/// [Grid#forEachSelectedCell] read the selected data, [Grid#focusedModelRowProperty()] follows the focused row
/// (for example for a detail form), and [Grid#selectModelRow] selects a data row wherever it is shown. Ctrl+C
/// copies the selection ([Grid#copySelection()]).
///
/// Right-clicking a cell opens the menu from [Grid#cellContextMenuFactoryProperty()]. Right-clicking a column
/// header, or Alt+Down, opens the column menu: sorting, fitting, hiding and showing columns, or the menu from
/// [Grid#columnMenuFactoryProperty()]. Cell tooltips show text that was cut, or the text from
/// [Grid#tooltipProviderProperty()] and a node from [Grid#tooltipGraphicProviderProperty()]; they have the style
/// class `grid-tooltip`. [Grid#placeholderProperty()] is shown while there are no rows. Built-in
/// texts are English or German, following the default display locale.
///
/// A form next to the grid is the recommended way to edit; [Grid#setEditable] turns on in-place editing. The
/// editor converts text with the column's converter, or else numbers in the default locale's format, dates, times,
/// enums and booleans; other types are not edited in place. Text that cannot be converted, or that the model
/// rejects, stays in the editor with a message. A click on a check box or Space toggles it.
///
/// [Grid#getLayout()] captures what a user changed (column order, explicit widths, hidden columns, sort, frozen
/// columns, zoom) as a [GridLayout] that encodes to a string; [Grid#onLayoutChangedProperty()] reports changes,
/// for example to save them.
///
/// The grid is always laid out left to right, also in a right-to-left scene.
///
/// ## Packages
///
/// | Package | Contents |
/// |---|---|
/// | `de.schladebeck.fx.grid` | the control ([Grid], [TreeGrid], [GridColumn], selection, layout) and its skin |
/// | [de.schladebeck.fx.grid.model] | where the data comes from |
/// | [de.schladebeck.fx.grid.render] | painting cells and headers: renderers, styles, text measurement |
/// | `de.schladebeck.fx.grid.internal` | building blocks that scale to any size; not exported, so not API |
///
/// ## Implementation notes
///
/// [Grid] is a facade over small parts, and so is its skin, which stays in this package to use package-private
/// parts of [Grid].
///
/// | Part | Job |
/// |---|---|
/// | `internal.ViewRows` | view rows to model rows: filter, then sort (or let the model sort) |
/// | `internal.RowSorting` | the sorts of `ViewRows` and the default order of values |
/// | `internal.ColumnLayout` | column order as runs of model columns, visibility, widths and limits |
/// | `internal.IndexMap` | the view to model mapping of the rows; the identity needs no arrays |
/// | `internal.Spans` | positions of rows and of columns: a default size plus only the exceptions |
/// | `internal.TextFitter` | text widths from character advances cached per font; cuts and wraps text |
/// | `ColumnSettings` | per-column settings besides layout (header style, comparator, ...), only where set |
/// | `RendererRegistry` | the default renderer choice per cell, with a small fixed cache |
/// | `GridSkin` | the nodes, layout, scroll bars, row positions, auto-fit, one repaint per pulse |
/// | `GridGeometry` | all viewport math, captured once per layout pass and shared by painting and input |
/// | `GridPainter` | paints from the geometry through one reused `CellContext`; measures on a hidden canvas |
/// | `GridBehavior` | mouse, keyboard, scroll, zoom and auto-scroll |
/// | `ColumnMenu`, `Texts` | the built-in column menu and the translated texts |
/// | `CellEditor`, `ValueParser` | the in-place editor and its default text conversion |
/// | `AccessibleCell` | the placeholders for assistive technology |
///
/// A frame: model events, scrolling and property changes only mark the skin dirty and request a layout pass. The
/// layout pass fits columns if needed, updates the geometry and the scroll bars, and repaints the canvas once.
/// The painter clears the canvas and paints the scrolled cells, then the frozen columns, the row header and the
/// column header on top. Within a region it paints all backgrounds, then the renderers column by column, then the
/// lines, so the canvas changes state rarely. Nothing is clipped: text is shortened or wrapped to fit, and later
/// regions cover what scrolled under them.
///
/// Memory and time do not grow with the model size: rows and columns that keep their defaults are not stored, and
/// only visible cells are asked for values. Sorting and filtering rows are the exceptions; they need an index
/// array of all rows.
package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.render.CellRenderer;
import de.schladebeck.fx.grid.render.CellRendererProvider;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.CellStyleProvider;

