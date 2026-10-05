package de.schladebeck.fx.grid;

import de.schladebeck.fx.grid.internal.Numbers;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.TemporalQuery;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/// Text for in-place editing and back, for cells without a converter of their own: text, numbers (in the default
/// locale's format without grouping, or plain), booleans, enum constants by name, and dates and times (ISO or the
/// default locale's medium and short formats). Other types are not edited.
final class ValueParser {

    /** Returned for text that is not a valid value of the type. */
    static final Object INVALID = new Object();

    /** Parsers of stripped, non-blank text by type; for other text they throw or return {@link #INVALID}. */
    private static final Map<Class<?>, Function<String, Object>> PARSERS = Map.ofEntries(
            Map.entry(Integer.class, Integer::valueOf),
            Map.entry(Long.class, Long::valueOf),
            Map.entry(Short.class, Short::valueOf),
            Map.entry(Byte.class, Byte::valueOf),
            Map.entry(BigInteger.class, BigInteger::new),
            Map.entry(Double.class, s -> localDecimal(s, false) instanceof Number n ? n.doubleValue()
                    : Double.valueOf(s)),
            Map.entry(Float.class, s -> localDecimal(s, false) instanceof Number n ? n.floatValue() : Float.valueOf(s)),
            Map.entry(BigDecimal.class, s -> localDecimal(s, true) instanceof Number n ? n : new BigDecimal(s)),
            Map.entry(Boolean.class, s -> s.equalsIgnoreCase("true") ? Boolean.TRUE
                    : s.equalsIgnoreCase("false") ? Boolean.FALSE : INVALID),
            Map.entry(LocalDate.class, s -> temporal(s, LocalDate::from, DateTimeFormatter.ISO_LOCAL_DATE,
                    DateTimeFormatter::ofLocalizedDate)),
            Map.entry(LocalDateTime.class, s -> temporal(s, LocalDateTime::from, DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                    DateTimeFormatter::ofLocalizedDateTime)),
            Map.entry(LocalTime.class, s -> temporal(s, LocalTime::from, DateTimeFormatter.ISO_LOCAL_TIME,
                    DateTimeFormatter::ofLocalizedTime)));

    private ValueParser() {
    }

    /** Whether values of the type can be edited as text without a converter. */
    static boolean supports(Class<?> type) {
        Class<?> t = boxed(type);
        return t == String.class || t == Object.class || t.isEnum() || PARSERS.containsKey(t);
    }

    /** The text the editor starts with: decimal numbers in the default locale without grouping, enums by name. */
    static String format(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault(Locale.Category.FORMAT));
            format.setGroupingUsed(false);
            format.setMaximumFractionDigits(value instanceof BigDecimal big ? Math.max(0, big.scale())
                    : Numbers.fractionDigits(value));
            return format.format(value);
        }
        return value instanceof Enum<?> constant ? constant.name() : value.toString();
    }

    /** Blank text becomes {@code null} for every type except String. */
    static Object parse(String text, Class<?> type) {
        Class<?> t = boxed(type);
        if (t == String.class || t == Object.class) {
            return text;
        }
        String s = text.strip();
        if (s.isEmpty()) {
            return null;
        }
        try {
            if (t.isEnum()) {
                for (Object constant : t.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equalsIgnoreCase(s)) {
                        return constant;
                    }
                }
                return INVALID;
            }
            Function<String, Object> parser = PARSERS.get(t);
            return parser == null ? INVALID : parser.apply(s);
        } catch (RuntimeException e) {
            return INVALID;
        }
    }

    /// A number in the default locale's format, without grouping, or `null` if the text is not one completely.
    /// Without grouping a German "1.5" is not read as 15, it falls through to the plain format and becomes 1.5.
    private static Number localDecimal(String text, boolean big) {
        NumberFormat local = NumberFormat.getNumberInstance(Locale.getDefault(Locale.Category.FORMAT));
        if (!(local instanceof DecimalFormat format)) {
            return null;
        }
        format.setGroupingUsed(false);
        format.setParseBigDecimal(big);
        ParsePosition position = new ParsePosition(0);
        Number number = format.parse(text, position);
        return number != null && position.getIndex() == text.length() ? number : null;
    }

    /** ISO first, then the default locale's medium and short formats. */
    private static <T> Object temporal(String text, TemporalQuery<T> query, DateTimeFormatter iso,
                                       Function<FormatStyle, DateTimeFormatter> localized) {
        for (DateTimeFormatter format : new DateTimeFormatter[] {
                iso, localized.apply(FormatStyle.MEDIUM), localized.apply(FormatStyle.SHORT)}) {
            try {
                return format.withLocale(Locale.getDefault(Locale.Category.FORMAT)).parse(text, query);
            } catch (RuntimeException next) {
                // try the next format
            }
        }
        return INVALID;
    }

    /** The wrapper class of a primitive type, other types as they are. */
    static Class<?> boxed(Class<?> type) {
        return type.isPrimitive() ? MethodType.methodType(type).wrap().returnType() : type;
    }
}
