package de.schladebeck.fx.grid.model;

import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;

/// Column definitions for models that show one object per row: a name, a getter, optionally an update function
/// for in-place editing. [ListGridModel], [PagedGridModel] and [TreeGridModel] each hold one. The items stay with
/// the model; methods here get the item they work on.
final class ItemColumns<T> {

    private record Column<T>(String name, Class<?> type, Function<? super T, ?> getter,
                             BiFunction<? super T, Object, ? extends T> updater) {
    }

    private final List<Column<T>> columns = new ArrayList<>();
    private Function<? super T, String> rowNames;

    /// Adds a column; `type` may be `null` to find the type from the values, `updater` `null` for read-only.
    @SuppressWarnings({"unchecked", "rawtypes"})
    void add(String name, Class<?> type, Function<? super T, ?> getter, BiFunction<? super T, ?, ? extends T> updater) {
        columns.add(new Column<>(name, type, getter, (BiFunction) updater));
    }

    /// Adds one column per record component, titled from the component name and typed by the component.
    /// The record must be public and in a package exported to module `de.schladebeck.fx.grid`, or on the class path.
    ///
    /// @throws IllegalArgumentException if this module cannot read the record's components
    void addRecordComponents(Class<? extends Record> type) {
        for (RecordComponent component : type.getRecordComponents()) {
            Method accessor = component.getAccessor();
            if (!accessor.trySetAccessible()) {
                throw new IllegalArgumentException(type.getName()
                        + " is not accessible to module de.schladebeck.fx.grid: make it public and add `exports "
                        + type.getPackageName() + " to de.schladebeck.fx.grid;` to module-info.java");
            }
            Class<?> valueType = MethodType.methodType(component.getType()).wrap().returnType();
            add(title(component.getName()), valueType, item -> read(accessor, item), null);
        }
    }

    void setRowNames(Function<? super T, String> names) {
        rowNames = names;
    }

    int count() {
        return columns.size();
    }

    String name(int column) {
        return columns.get(column).name();
    }

    /// The value of `item` in `column`; `null` for a missing item, e.g. one that is still loading.
    Object value(T item, int column) {
        return item == null ? null : columns.get(column).getter().apply(item);
    }

    String rowName(T item) {
        return rowNames == null || item == null ? null : rowNames.apply(item);
    }

    /// The declared type, otherwise the class of the first non-null value among the first 10 of `available` items.
    Class<?> type(int column, IntFunction<T> items, int available) {
        Column<T> col = columns.get(column);
        if (col.type() != null) {
            return col.type();
        }
        for (int row = 0; row < Math.min(available, 10); row++) {
            Object value = value(items.apply(row), column);
            if (value != null) {
                return value.getClass();
            }
        }
        return Object.class;
    }

    boolean editable(int column) {
        return columns.get(column).updater() != null;
    }

    /// The item to store after editing: what the column's update function makes of `item` and `value`.
    T update(T item, int column, Object value) {
        Column<T> col = columns.get(column);
        if (col.updater() == null) {
            throw new UnsupportedOperationException("Column " + col.name() + " has no update function");
        }
        return col.updater().apply(item, value);
    }

    /// `"firstName"` becomes `"First name"`.
    static String title(String name) {
        StringBuilder title = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i == 0) {
                title.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c)) {
                title.append(' ').append(Character.toLowerCase(c));
            } else {
                title.append(c);
            }
        }
        return title.toString();
    }

    private static Object read(Method accessor, Object item) {
        try {
            return accessor.invoke(item);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(accessor.getDeclaringClass().getName()
                    + " must be public and exported to module de.schladebeck.fx.grid", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
