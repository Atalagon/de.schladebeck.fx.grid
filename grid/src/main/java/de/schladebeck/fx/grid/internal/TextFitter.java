package de.schladebeck.fx.grid.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/// Fast text widths: each character's advance is measured once per font with a [Text] node and cached, and a
/// string's width is the sum of its advances (JavaFX has no public font metrics API, and a [Text] node per string
/// is too slow). Ignores kerning and complex scripts, which is good enough for cutting text and fitting columns.
public final class TextFitter {

    public static final String ELLIPSIS = "…";

    private final Text probe = new Text();
    private final Map<Font, float[][]> pagesByFont = new HashMap<>();
    private final Map<Font, Double> lineHeights = new HashMap<>();
    private Font lastFont;
    private float[][] lastPages;

    /// Returns `text` itself if it fits, otherwise a prefix plus an ellipsis, or "" if not even the ellipsis fits.
    /// Measures only about as many characters as fit, so a long text costs no more than a short one.
    public String fit(String text, Font font, double maxWidth) {
        return width(text, font, maxWidth) <= maxWidth ? text : truncate(text, font, maxWidth);
    }

    public double width(String text, Font font) {
        return width(text, font, Double.POSITIVE_INFINITY);
    }

    /// [#width(String, Font)] if that is at most `cap`, otherwise some value above `cap`: stops summing as soon as
    /// the width exceeds `cap`.
    public double width(String text, Font font, double cap) {
        float[][] pages = pages(font);
        double width = 0;
        for (int i = 0; i < text.length() && width <= cap; i++) {
            width += advance(pages, font, text.charAt(i));
        }
        return width;
    }

    /** A prefix of {@code text} plus an ellipsis that fits {@code maxWidth}, or "" if not even the ellipsis fits. */
    public String truncate(String text, Font font, double maxWidth) {
        return truncate(text, 0, font, maxWidth, '\n');
    }

    /** {@link #truncate(String, Font, double)} from {@code start} on, with each {@code \n} as {@code newline}. */
    private String truncate(String text, int start, Font font, double maxWidth, char newline) {
        float[][] pages = pages(font);
        double available = maxWidth - advance(pages, font, ELLIPSIS.charAt(0));
        if (available < 0) {
            return "";
        }
        double width = 0;
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            double w = advance(pages, font, c == '\n' ? newline : c);
            if (width + w > available) {
                break;
            }
            width += w;
            end++;
        }
        if (end > start && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end).replace('\n', newline) + ELLIPSIS;
    }

    /** Height of one line of text in {@code font}. */
    public double lineHeight(Font font) {
        return lineHeights.computeIfAbsent(font, f -> {
            probe.setFont(f);
            probe.setText("Hg");
            return probe.getLayoutBounds().getHeight();
        });
    }

    /// Breaks `text` into lines no wider than `maxWidth`: at spaces, inside words longer than a line,
    /// and at line breaks in the text. If more than `maxLines` lines would be needed, the last line holds the
    /// rest of the text, cut off with an ellipsis.
    public List<String> wrap(String text, Font font, double maxWidth, int maxLines) {
        float[][] pages = pages(font);
        List<String> lines = new ArrayList<>();
        int length = text.length();
        int start = 0;
        while (true) {
            int end = start;
            int lastSpace = -1;
            double width = 0;
            while (end < length && text.charAt(end) != '\n') {
                char c = text.charAt(end);
                double w = advance(pages, font, c);
                if (width + w > maxWidth && end > start) {
                    break;
                }
                if (c == ' ') {
                    lastSpace = end;
                }
                width += w;
                end++;
            }
            int lineEnd = end;
            int next = end;
            if (end < length && (text.charAt(end) == '\n' || text.charAt(end) == ' ')) {
                next = end + 1;
            } else if (end < length && lastSpace > start) {
                lineEnd = lastSpace;
                next = lastSpace + 1;
            }
            if (next < length && lines.size() == maxLines - 1) {
                lines.add(truncate(text, start, font, maxWidth, ' '));
                return lines;
            }
            lines.add(text.substring(start, lineEnd));
            if (next >= length) {
                return lines;
            }
            start = next;
        }
    }

    private float[][] pages(Font font) {
        if (font != lastFont) {
            lastPages = pagesByFont.computeIfAbsent(font, f -> new float[256][]);
            lastFont = font;
        }
        return lastPages;
    }

    private float advance(float[][] pages, Font font, char c) {
        float[] page = pages[c >>> 8];
        if (page == null) {
            page = new float[256];
            Arrays.fill(page, Float.NaN);
            pages[c >>> 8] = page;
        }
        float advance = page[c & 0xFF];
        if (Float.isNaN(advance)) {
            advance = (float) measure(font, c);
            page[c & 0xFF] = advance;
        }
        return advance;
    }

    /// Measured between two letters, because a lone or trailing space has no logical width in a Text node.
    /// Line breaks get no width: in the probe they start a new line, which would make their width negative.
    private double measure(Font font, char c) {
        if (c == '\n' || c == '\r') {
            return 0;
        }
        probe.setFont(font);
        probe.setText("H" + c + "H");
        double with = probe.getLayoutBounds().getWidth();
        probe.setText("HH");
        return with - probe.getLayoutBounds().getWidth();
    }
}
