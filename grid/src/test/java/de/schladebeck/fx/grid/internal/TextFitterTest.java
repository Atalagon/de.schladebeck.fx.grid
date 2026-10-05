package de.schladebeck.fx.grid.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.Fx;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TextFitterTest {

    private TextFitter fitter;
    private Font font;

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    @Test
    void fitTruncateAndWidthMatchTheOldAlgorithms() throws Exception {
        run(() -> {
            Random random = new Random(42);
            for (int n = 0; n < 5_000; n++) {
                String text = randomText(random);
                double total = oldWidth(text);
                assertEquals(total, fitter.width(text, font), text);
                for (double maxWidth : widths(random, total)) {
                    String expected = oldFit(text, maxWidth);
                    String fitted = fitter.fit(text, font, maxWidth);
                    assertEquals(expected, fitted, () -> describe(text, maxWidth));
                    if (expected == text) {
                        assertSame(text, fitted);
                    }
                    assertEquals(oldTruncate(text, maxWidth), fitter.truncate(text, font, maxWidth));

                    double capped = fitter.width(text, font, maxWidth);
                    if (total <= maxWidth) {
                        assertEquals(total, capped, () -> describe(text, maxWidth));
                    } else {
                        assertTrue(capped > maxWidth, () -> capped + " for " + describe(text, maxWidth));
                    }
                }
            }
        });
    }

    @Test
    void wrapMatchesTheOldAlgorithm() throws Exception {
        run(() -> {
            Random random = new Random(7);
            for (int n = 0; n < 5_000; n++) {
                String text = randomText(random);
                for (double maxWidth : widths(random, oldWidth(text))) {
                    for (int maxLines : new int[] {1, 2, 1 + random.nextInt(6), Integer.MAX_VALUE}) {
                        assertEquals(oldWrap(text, maxWidth, maxLines), fitter.wrap(text, font, maxWidth, maxLines),
                                () -> describe(text, maxWidth) + ", " + maxLines + " lines");
                    }
                }
            }
        });
    }

    /** TextFitter.width(String, Font, double) relies on this to stop early. */
    @Test
    void noCharacterHasANegativeWidth() throws Exception {
        run(() -> {
            List<Character> negative = new ArrayList<>();
            for (int c = 0; c <= Character.MAX_VALUE; c++) {
                if (fitter.width(String.valueOf((char) c), font) < 0) {
                    negative.add((char) c);
                }
            }
            assertEquals(List.of(), negative);
            assertEquals(fitter.width("abcd", font), fitter.width("ab\ncd\r\n", font));
        });
    }

    @Test
    void edgeCases() throws Exception {
        run(() -> {
            double ellipsis = fitter.width(TextFitter.ELLIPSIS, font);

            assertSame("", fitter.fit("", font, 0));
            assertEquals("", fitter.fit("", font, -1));
            assertEquals(0, fitter.width("", font, 0));
            assertEquals(TextFitter.ELLIPSIS, fitter.truncate("", font, 100));
            assertEquals(List.of(""), fitter.wrap("", font, 100, 3));

            String spaces = "     ";
            assertSame(spaces, fitter.fit(spaces, font, 100));
            assertEquals(oldFit(spaces, 5), fitter.fit(spaces, font, 5));
            assertEquals(oldWrap(spaces, 5, 3), fitter.wrap(spaces, font, 5, 3));

            assertEquals("", fitter.fit("abc", font, ellipsis - 0.01));
            assertEquals("", fitter.truncate("abc", font, ellipsis - 0.01));
            assertEquals(List.of(""), fitter.wrap("abc def", font, ellipsis - 0.01, 1));
            assertEquals(TextFitter.ELLIPSIS, fitter.fit("abc", font, ellipsis));

            String emojis = "\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02";
            for (double maxWidth = 0; maxWidth < 60; maxWidth += 0.25) {
                String fitted = fitter.fit(emojis, font, maxWidth);
                assertEquals(oldFit(emojis, maxWidth), fitted);
                int cut = fitted.length() - TextFitter.ELLIPSIS.length();
                assertFalse(fitted.endsWith(TextFitter.ELLIPSIS) && cut > 0
                        && Character.isHighSurrogate(fitted.charAt(cut - 1)), fitted);
            }
        });
    }

    @Test
    void lineHeightIsTheHeightOfOneLine() throws Exception {
        run(() -> {
            Text probe = new Text("Hg");
            probe.setFont(font);
            assertEquals(probe.getLayoutBounds().getHeight(), fitter.lineHeight(font));
            assertEquals(fitter.lineHeight(font), fitter.lineHeight(font));
            assertTrue(fitter.lineHeight(Font.font(26)) > fitter.lineHeight(font));
        });
    }

    @Test
    void fittingALongTextOnlyCostsTheVisiblePart() throws Exception {
        run(() -> {
            Random random = new Random(1);
            StringBuilder builder = new StringBuilder();
            while (builder.length() < 10_000) {
                builder.append(word(random, 1 + random.nextInt(10))).append(' ');
            }
            String text = builder.substring(0, 10_000);
            String lines = text.replace("q", "\n");

            int calls = 10_000;
            for (int warmUp = 0; warmUp < 2; warmUp++) {
                for (int i = 0; i < calls; i++) {
                    fitter.fit(text, font, 100);
                    fitter.fit(lines, font, 100);
                    fitter.width(text, font);
                }
            }
            double fit = nanosPerCall(() -> fitter.fit(text, font, 100), calls);
            double fitLines = nanosPerCall(() -> fitter.fit(lines, font, 100), calls);
            double width = nanosPerCall(() -> fitter.width(text, font), calls);
            String numbers = "fit %.0f ns, fit with line breaks %.0f ns, full width %.0f ns"
                    .formatted(fit, fitLines, width);
            assertTrue(fit < 100_000 && fitLines < 100_000, numbers);
            assertTrue(fit * 5 < width && fitLines * 5 < width, numbers);
        });
    }

    private void run(Runnable test) throws Exception {
        Fx.run(() -> {
            fitter = new TextFitter();
            font = Font.font(13);
            test.run();
        });
    }

    private static double nanosPerCall(Runnable action, int calls) {
        long start = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            action.run();
        }
        return (System.nanoTime() - start) / (double) calls;
    }

    /// Letters, spaces, line breaks, surrogate pairs and long words; now and then a very long text.
    private static String randomText(Random random) {
        StringBuilder text = new StringBuilder();
        int parts = random.nextInt(10) == 0 ? 400 : random.nextInt(14);
        for (int i = 0; i < parts; i++) {
            switch (random.nextInt(9)) {
                case 0, 1 -> text.append(' ');
                case 2 -> text.append('\n');
                case 3 -> text.append(random.nextBoolean() ? "\r\n" : "\r");
                case 4 -> text.appendCodePoint(0x1F600 + random.nextInt(64));
                case 5 -> text.append(word(random, 20 + random.nextInt(80)));
                default -> text.append(word(random, 1 + random.nextInt(9)));
            }
        }
        return text.toString();
    }

    private static String word(Random random, int length) {
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < length; i++) {
            word.append((char) (random.nextInt(4) == 0 ? 'A' + random.nextInt(26) : 'a' + random.nextInt(26)));
        }
        return word.toString();
    }

    /** Fixed small widths, widths around the text's own width, and random ones. */
    private static double[] widths(Random random, double total) {
        return new double[] {-1, 0, 5, total, total - 0.01, total * random.nextDouble(), random.nextDouble() * 300};
    }

    private static String describe(String text, double maxWidth) {
        return "\"" + text.replace("\n", "\\n").replace("\r", "\\r") + "\" in " + maxWidth;
    }

    private double advance(char c) {
        return c == '\n' || c == '\r' ? 0 : fitter.width(String.valueOf(c), font);
    }

    // The algorithms before fit() and wrap() stopped early, as the reference (with line breaks of no width).

    private double oldWidth(String text) {
        double width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += advance(text.charAt(i));
        }
        return width;
    }

    private String oldFit(String text, double maxWidth) {
        return oldWidth(text) <= maxWidth ? text : oldTruncate(text, maxWidth);
    }

    private String oldTruncate(String text, double maxWidth) {
        double available = maxWidth - advance(TextFitter.ELLIPSIS.charAt(0));
        if (available < 0) {
            return "";
        }
        double width = 0;
        int end = 0;
        while (end < text.length()) {
            double w = advance(text.charAt(end));
            if (width + w > available) {
                break;
            }
            width += w;
            end++;
        }
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + TextFitter.ELLIPSIS;
    }

    private List<String> oldWrap(String text, double maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        int length = text.length();
        int start = 0;
        while (true) {
            int end = start;
            int lastSpace = -1;
            double width = 0;
            while (end < length && text.charAt(end) != '\n') {
                char c = text.charAt(end);
                double w = advance(c);
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
                lines.add(oldTruncate(text.substring(start).replace('\n', ' '), maxWidth));
                return lines;
            }
            lines.add(text.substring(start, lineEnd));
            if (next >= length) {
                return lines;
            }
            start = next;
        }
    }
}
