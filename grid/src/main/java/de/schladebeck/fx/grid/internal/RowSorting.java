package de.schladebeck.fx.grid.internal;

import de.schladebeck.fx.grid.model.GridModel;
import de.schladebeck.fx.grid.model.SortKey;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntBinaryOperator;

/// The stable sorts of [ViewRows] and the default order of values. Numbers in the default order and values that
/// repeat are radix sorted by primitive keys, other text in the default order by the library's sort over folded
/// text, anything else by a merge sort with the key's comparator.
final class RowSorting {

    /// The order of a column's values without a comparator of its own: nulls first, numbers by value, text
    /// case-insensitively (case decides ties), enums by declaring class and ordinal, other values of one class
    /// naturally, anything else by its text.
    static final Comparator<Object> DEFAULT_ORDER = RowSorting::compare;
    /** Values looked at to guess whether a column's values repeat. */
    private static final int REPEAT_PROBE = 16_384;
    /** Fewer rows are merge sorted: setting up the other sorts would cost more than it saves. */
    private static final int FEW_ROWS = 64;
    /** Longs up to this magnitude convert to double exactly. */
    private static final long EXACT_LONG = 1L << 53;

    /// A sort key: a model column and the order of its values, `values` ascending with nulls first, `view` in the
    /// key's direction.
    record Key(int column, boolean ascending, Comparator<Object> values, Comparator<Object> view) {

        static Key of(SortKey key, Comparator<Object> comparator) {
            Comparator<Object> values = comparator == null ? DEFAULT_ORDER : Comparator.nullsFirst(comparator);
            return new Key(key.column(), key.ascending(), values, key.ascending() ? values : values.reversed());
        }

        boolean defaultOrder() {
            return values == DEFAULT_ORDER;
        }
    }

    private RowSorting() {
    }

    /// The rows, in model order, sorted by the sort keys: the first key decides, the later ones break its ties.
    /// Only the ties are sorted by the later keys, so after a first key whose values rarely repeat they cost
    /// little.
    static int[] sorted(GridModel model, List<Key> keys, int[] rows) {
        Key first = keys.getFirst();
        int[] sorted = sortedBy(model, first, rows);
        if (keys.size() == 1) {
            return sorted;
        }
        int start = 0;
        Object startValue = sorted.length == 0 ? null : model.getValueAt(sorted[0], first.column());
        for (int i = 1; i <= sorted.length; i++) {
            Object value = i < sorted.length ? model.getValueAt(sorted[i], first.column()) : null;
            if (i == sorted.length || !tied(first, startValue, value)) {
                if (i - start > 1) { // tied rows are still in model order, the sort is stable
                    int[] tied = sorted(model, keys.subList(1, keys.size()), Arrays.copyOfRange(sorted, start, i));
                    System.arraycopy(tied, 0, sorted, start, tied.length);
                }
                start = i;
                startValue = value;
            }
        }
        return sorted;
    }

    /** Whether the key orders two values alike. Texts in the default order are only alike when equal. */
    private static boolean tied(Key key, Object a, Object b) {
        if (key.defaultOrder() && a instanceof String && b instanceof String) {
            return a.equals(b);
        }
        return key.values().compare(a, b) == 0;
    }

    /** The rows sorted stably by one key; rows with equal values keep their order. */
    private static int[] sortedBy(GridModel model, Key key, int[] rows) {
        int n = rows.length;
        Object[] values = new Object[n];
        for (int i = 0; i < n; i++) {
            values[i] = model.getValueAt(rows[i], key.column());
        }
        if (n >= FEW_ROWS) {
            long[] keys = key.defaultOrder() ? numberKeys(values, key.ascending()) : null;
            if (keys == null && repeats(values)) {
                keys = rankKeys(values, key);
            }
            if (keys != null) {
                return radixSort(keys, rows);
            }
            if (key.defaultOrder() && Arrays.stream(values).allMatch(v -> v == null || v instanceof String)) {
                return sortedText(values, rows, key.ascending());
            }
        }
        int[] order = new int[n];
        Arrays.setAll(order, i -> i);
        Comparator<Object> c = key.view();
        mergeSort(order, (a, b) -> c.compare(values[a], values[b]));
        Arrays.setAll(order, i -> rows[order[i]]);
        return order;
    }

    /// Whether values repeat enough for [#rankKeys] to pay off, estimated from the duplicates among the first
    /// values: `k` values drawn from `d` distinct ones hold about `k² / 2d` duplicates. Both ways sort alike, so
    /// a wrong guess costs time only.
    private static boolean repeats(Object[] values) {
        int probe = Math.min(values.length, REPEAT_PROBE);
        Set<Object> seen = new HashSet<>();
        for (int i = 0; i < probe; i++) {
            seen.add(values[i]);
        }
        int duplicates = probe - seen.size();
        return duplicates > 0 && (double) probe * probe / (2.0 * duplicates) < values.length / 2.0;
    }

    /** A row's text with its case-insensitive form, computed once instead of in every comparison. */
    private record Text(String folded, String text, int row) {
    }

    /// Text in the default order, sorted by the library's stable sort over the folded texts; they order exactly
    /// like [#compareText].
    private static int[] sortedText(Object[] values, int[] rows, boolean ascending) {
        Text[] texts = new Text[rows.length];
        for (int i = 0; i < texts.length; i++) {
            String text = (String) values[i];
            texts[i] = new Text(text == null ? null : fold(text), text, rows[i]);
        }
        Comparator<Text> order = RowSorting::compareFolded;
        Arrays.sort(texts, ascending ? order : order.reversed());
        int[] sorted = new int[texts.length];
        Arrays.setAll(sorted, i -> texts[i].row());
        return sorted;
    }

    private static int compareFolded(Text a, Text b) {
        if (a.text() == null || b.text() == null) {
            return a.text() == null ? (b.text() == null ? 0 : -1) : 1;
        }
        int c = a.folded().compareTo(b.folded());
        return c != 0 ? c : a.text().compareTo(b.text());
    }

    /// Keys whose order is the view order, for values of any kind: each value's rank among the distinct values.
    /// Values often repeat, as text does, and then only the distinct ones are compared. Values the order finds
    /// equal share a rank.
    private static long[] rankKeys(Object[] values, Key key) {
        Map<Object, int[]> ranks = new HashMap<>();
        int[][] rankOf = new int[values.length][];
        for (int i = 0; i < values.length; i++) {
            rankOf[i] = ranks.computeIfAbsent(values[i], value -> new int[1]);
        }
        Object[] distinct = ranks.keySet().toArray();
        Comparator<Object> c = key.view();
        if (key.defaultOrder() && Arrays.stream(distinct).allMatch(v -> v == null || v instanceof String)) {
            Comparator<Object> text = RowSorting::compareTextOrNull; // the library's sort is faster, and safe for text
            Arrays.sort(distinct, key.ascending() ? text : text.reversed());
        } else {
            int[] order = new int[distinct.length];
            Arrays.setAll(order, i -> i);
            mergeSort(order, (a, b) -> c.compare(distinct[a], distinct[b]));
            Object[] unsorted = distinct.clone();
            Arrays.setAll(distinct, i -> unsorted[order[i]]);
        }
        int rank = 0;
        for (int i = 0; i < distinct.length; i++) {
            if (i > 0 && c.compare(distinct[i - 1], distinct[i]) != 0) {
                rank++;
            }
            ranks.get(distinct[i])[0] = rank;
        }
        long[] keys = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            keys[i] = rankOf[i][0];
        }
        return keys;
    }

    /// Keys whose unsigned order is the view order, if every non-null value is a number that a double holds
    /// exactly, otherwise `null`. The default order compares such numbers as doubles, nulls first.
    private static long[] numberKeys(Object[] values, boolean ascending) {
        for (Object value : values) {
            if (value != null && !exactDouble(value)) {
                return null;
            }
        }
        long[] keys = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            long key = 0; // null: no double maps to it
            if (values[i] instanceof Number number) {
                long bits = Double.doubleToLongBits(number.doubleValue());
                key = bits ^ ((bits >> 63) | Long.MIN_VALUE); // unsigned order is now Double.compare's order
            }
            keys[i] = ascending ? key : ~key;
        }
        return keys;
    }

    /// Stable LSD radix sort of `rows` by unsigned `keys`, one byte per pass. Skips bytes all keys share, such as
    /// the low bytes of whole numbers. Uses both arrays as buffers.
    private static int[] radixSort(long[] keys, int[] rows) {
        int n = rows.length;
        long[] keyBuffer = new long[n];
        int[] rowBuffer = new int[n];
        int[] start = new int[257];
        for (int shift = 0; shift < 64; shift += 8) {
            Arrays.fill(start, 0);
            for (long key : keys) {
                start[(int) (key >>> shift & 0xFF) + 1]++;
            }
            if (n == 0 || start[(int) (keys[0] >>> shift & 0xFF) + 1] == n) {
                continue;
            }
            for (int digit = 0; digit < 256; digit++) {
                start[digit + 1] += start[digit];
            }
            for (int i = 0; i < n; i++) {
                int to = start[(int) (keys[i] >>> shift & 0xFF)]++;
                keyBuffer[to] = keys[i];
                rowBuffer[to] = rows[i];
            }
            long[] swapKeys = keys;
            keys = keyBuffer;
            keyBuffer = swapKeys;
            int[] swapRows = rows;
            rows = rowBuffer;
            rowBuffer = swapRows;
        }
        return rows;
    }

    private static boolean exactDouble(Object value) {
        return value instanceof Double || value instanceof Integer || value instanceof Float
                || value instanceof Short || value instanceof Byte
                || value instanceof Long l && l >= -EXACT_LONG && l <= EXACT_LONG;
    }

    /** Bottom-up merge sort. Takes from the right run only if strictly smaller, which keeps it stable. */
    static void mergeSort(int[] a, IntBinaryOperator order) {
        int n = a.length;
        int[] src = a;
        int[] dst = new int[n];
        for (int width = 1; width < n; width *= 2) {
            for (int lo = 0; lo < n; lo += 2 * width) {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + 2 * width, n);
                int i = lo;
                int j = mid;
                int k = lo;
                while (i < mid && j < hi) {
                    dst[k++] = order.applyAsInt(src[j], src[i]) < 0 ? src[j++] : src[i++];
                }
                while (i < mid) {
                    dst[k++] = src[i++];
                }
                while (j < hi) {
                    dst[k++] = src[j++];
                }
            }
            int[] swap = src;
            src = dst;
            dst = swap;
        }
        if (src != a) {
            System.arraycopy(src, 0, a, 0, n);
        }
    }

    /** The {@link #DEFAULT_ORDER}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a == b) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof String x && b instanceof String y) {
            return compareText(x, y);
        }
        if (a instanceof Enum<?> x && b instanceof Enum<?> y) {
            int byClass = x.getDeclaringClass().getName().compareTo(y.getDeclaringClass().getName());
            return byClass != 0 ? byClass : Integer.compare(x.ordinal(), y.ordinal());
        }
        if (a.getClass() == b.getClass() && a instanceof Comparable comparable) {
            return comparable.compareTo(b);
        }
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        return compareText(a.toString(), b.toString());
    }

    private static int compareTextOrNull(Object a, Object b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        return compareText((String) a, (String) b);
    }

    /// Case-insensitively like `String.CASE_INSENSITIVE_ORDER`, then case decides. Compares the chars itself:
    /// faster than the library order, as only the first different chars are converted.
    private static int compareText(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            char x = a.charAt(i);
            char y = b.charAt(i);
            if (x != y && fold(x) != fold(y)) {
                return fold(x) - fold(y);
            }
        }
        int c = a.length() - b.length();
        return c != 0 ? c : a.compareTo(b);
    }

    /** The text with each char as {@link #fold(char)} makes it. */
    private static String fold(String text) {
        char[] chars = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (fold(c) != c) {
                if (chars == null) {
                    chars = text.toCharArray();
                }
                chars[i] = fold(c);
            }
        }
        return chars == null ? text : new String(chars);
    }

    /** A char as String.CASE_INSENSITIVE_ORDER compares it: lower case of upper case. */
    private static char fold(char c) {
        return Character.toLowerCase(Character.toUpperCase(c));
    }
}
