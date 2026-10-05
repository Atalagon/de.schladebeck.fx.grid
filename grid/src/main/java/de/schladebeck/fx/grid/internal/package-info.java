/// Building blocks of the grid that scale to any number of rows and columns. The package is not exported:
/// its classes are public only so the other packages of the module can use them.
///
/// | Class | Job |
/// |---|---|
/// | [ViewRows] | view rows to model rows: filter, then sort, or let the model sort itself |
/// | [RowSorting] | the sorts of [ViewRows] and the default order of values |
/// | [ColumnLayout] | column order, visibility, widths and their limits |
/// | [IndexMap] | the view to model mapping of [ViewRows]; the identity needs no arrays |
/// | [Spans] | positions of rows or columns: a default size plus only the exceptions, found by binary search |
/// | [TextFitter] | text widths from character advances cached per font; cuts and wraps text |
///
/// Everything here stores only what differs from a default. A grid with 100 million rows or columns that nobody
/// sorted, moved, hid or resized holds a handful of objects, and each lookup is constant or logarithmic.
package de.schladebeck.fx.grid.internal;
