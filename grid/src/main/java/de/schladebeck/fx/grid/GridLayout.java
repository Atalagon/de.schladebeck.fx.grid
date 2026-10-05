package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.model.SortKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/// What a user arranged in a grid: column order, widths and visibility, sorting, frozen columns and rows, and zoom.
/// Get it with
/// [Grid#getLayout()], store it as a string with [#encode()], and bring it back with [#decode(String)] and
/// [Grid#setLayout(GridLayout)]:
///
/// ```java
/// preferences.put("grid", grid.getLayout().encode());
/// grid.setLayout(GridLayout.decode(preferences.get("grid", null)));   // nothing saved yet: no change
/// ```
///
/// Only what differs from the defaults is kept, so a layout stays small for any number of columns: the order as
/// ranges of model columns, and only widths the user or code set. Column indices are model columns, and the names
/// of the columns a layout mentions are kept with it: a layout saved before columns were added or removed is
/// applied by name, and leaves out what it cannot find.
///
/// @param columnOrder   ranges of model columns in view order, hidden ones included; empty for the natural order
/// @param columnWidths  model columns whose width the user or code set, and that width
/// @param hiddenColumns hidden model columns
/// @param sortKeys      the columns the rows are sorted by, the first one decides; empty when unsorted
/// @param frozenColumns number of frozen leading columns
/// @param frozenRows    number of frozen leading rows
/// @param zoom          zoom factor
/// @param columnNames   names of the model columns the other parts mention, to find them again
public record GridLayout(List<ColumnRange> columnOrder, Map<Integer, Double> columnWidths, Set<Integer> hiddenColumns,
                         List<SortKey> sortKeys, int frozenColumns, int frozenRows, double zoom,
                         Map<Integer, String> columnNames) {

    /// Consecutive model columns, shown next to each other in this order.
    ///
    /// @param first the first model column
    /// @param last  the last model column, at least `first`
    public record ColumnRange(int first, int last) {

        /** Checks that the range holds at least one column. */
        public ColumnRange {
            if (first < 0 || last < first) {
                throw new IllegalArgumentException("Not a column range: " + first + "-" + last);
            }
        }
    }

    /** Copies the collections, so a layout never changes. */
    public GridLayout {
        columnOrder = List.copyOf(columnOrder);
        columnWidths = Collections.unmodifiableMap(new TreeMap<>(columnWidths));
        hiddenColumns = Collections.unmodifiableSet(new TreeSet<>(hiddenColumns));
        sortKeys = List.copyOf(sortKeys);
        columnNames = Collections.unmodifiableMap(new TreeMap<>(columnNames));
    }

    /// A compact text form for preferences or files, for example:
    /// ```
    /// v=1;order=2,0-1,3-99;widths=0:120,2:95.5;hidden=1;sort=2:desc,0:asc;frozen=1;frozenRows=2;zoom=1;names=0:ID
    /// ```
    ///
    /// @return the layout as text
    public String encode() {
        String order = columnOrder.stream()
                .map(range -> range.first() + (range.last() > range.first() ? "-" + range.last() : ""))
                .collect(Collectors.joining(","));
        String widths = columnWidths.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + number(entry.getValue()))
                .collect(Collectors.joining(","));
        String hidden = hiddenColumns.stream().map(String::valueOf).collect(Collectors.joining(","));
        String sort = sortKeys.stream()
                .map(key -> key.column() + (key.ascending() ? ":asc" : ":desc"))
                .collect(Collectors.joining(","));
        String names = columnNames.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + escape(entry.getValue()))
                .collect(Collectors.joining(","));
        return "v=1;order=" + order
                + ";widths=" + widths
                + ";hidden=" + hidden
                + ";sort=" + sort
                + ";frozen=" + frozenColumns
                + ";frozenRows=" + frozenRows
                + ";zoom=" + number(zoom)
                + ";names=" + names;
    }

    /// Reads the form written by [#encode()]. Missing or unreadable parts get their defaults: natural column order,
    /// default widths, nothing hidden, unsorted, nothing frozen, zoom 1. Widths and zoom that are not positive
    /// finite numbers are left out.
    ///
    /// @param text the encoded layout, may be `null`
    /// @return the layout, or `null` for `null` or blank text, so restoring nothing changes nothing
    public static GridLayout decode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<ColumnRange> order = List.of();
        Map<Integer, Double> widths = Map.of();
        Set<Integer> hidden = Set.of();
        List<SortKey> sort = List.of();
        Map<Integer, String> names = Map.of();
        int frozen = 0;
        int frozenRows = 0;
        double zoom = 1;
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq).strip();
            String value = part.substring(eq + 1).strip();
            try {
                switch (key) {
                    case "order" -> order = ranges(value);
                    case "widths" -> widths = widths(value);
                    case "hidden" -> hidden = items(value).stream().map(Integer::valueOf).collect(Collectors.toSet());
                    case "sort" -> sort = sortKeys(value);
                    case "names" -> names = names(value);
                    case "frozen" -> frozen = Integer.parseInt(value);
                    case "frozenRows" -> frozenRows = Integer.parseInt(value);
                    case "zoom" -> {
                        double z = Double.parseDouble(value);
                        zoom = Double.isFinite(z) && z > 0 ? z : 1;
                    }
                    default -> {
                    }
                }
            } catch (RuntimeException unreadable) {
                // keep the default for this part
            }
        }
        return new GridLayout(order, widths, hidden, sort, frozen, frozenRows, zoom, names);
    }

    private static Map<Integer, String> names(String value) {
        Map<Integer, String> result = new TreeMap<>();
        for (String item : value.split(",")) {
            int colon = item.indexOf(':');
            if (colon > 0) {
                result.put(Integer.parseInt(item.substring(0, colon).strip()), unescape(item.substring(colon + 1)));
            }
        }
        return result;
    }

    /** Column names may hold the characters that separate the parts of a layout, so those are written as %XX. */
    private static String escape(String name) {
        return name.replace("%", "%25").replace(";", "%3B").replace(",", "%2C").replace(":", "%3A")
                .replace("=", "%3D");
    }

    private static String unescape(String text) {
        return text.replace("%3B", ";").replace("%2C", ",").replace("%3A", ":").replace("%3D", "=")
                .replace("%25", "%");
    }

    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /** The items of a comma-separated list, stripped, without blank ones. */
    private static List<String> items(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(item -> !item.isEmpty()).toList();
    }

    private static List<ColumnRange> ranges(String value) {
        List<ColumnRange> result = new ArrayList<>();
        for (String item : items(value)) {
            int dash = item.indexOf('-', 1);
            int first = Integer.parseInt(item.substring(0, dash < 0 ? item.length() : dash).strip());
            int last = dash < 0 ? first : Integer.parseInt(item.substring(dash + 1).strip());
            result.add(new ColumnRange(first, last));
        }
        return result;
    }

    /** Keys like {@code 2:desc,0:asc}; negative columns, the unsorted state of older layouts, are left out. */
    private static List<SortKey> sortKeys(String value) {
        List<SortKey> result = new ArrayList<>();
        for (String item : items(value)) {
            String[] key = item.split(":");
            int column = Integer.parseInt(key[0].strip());
            if (column >= 0) {
                result.add(new SortKey(column, key.length < 2 || !key[1].strip().equalsIgnoreCase("desc")));
            }
        }
        return result;
    }

    private static Map<Integer, Double> widths(String value) {
        Map<Integer, Double> result = new TreeMap<>();
        for (String item : items(value)) {
            String[] entry = item.split(":");
            double width = Double.parseDouble(entry[1].strip());
            if (Double.isFinite(width) && width > 0) {
                result.put(Integer.parseInt(entry[0].strip()), width);
            }
        }
        return result;
    }
}
