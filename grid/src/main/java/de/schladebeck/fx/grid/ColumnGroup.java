package de.schladebeck.fx.grid;

/// One row of column groups above the column headers, see [Grid#columnGroupsProperty()]: the name of the group a
/// model column belongs to at this level, or `null` for none. Neighbouring columns with the same name share one
/// header cell.
@FunctionalInterface
public interface ColumnGroup {

    /// The group of a model column at this level.
    ///
    /// @param modelColumn the model column
    /// @return the group's name, or `null` if the column has no group here
    String nameOf(int modelColumn);
}
