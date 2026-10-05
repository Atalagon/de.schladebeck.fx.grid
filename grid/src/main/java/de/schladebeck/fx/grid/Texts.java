package de.schladebeck.fx.grid;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/// The texts the grid shows itself (column menu, editing and copy messages, placeholder) in English, with a German
/// translation in `texts_de.properties`. The language follows the default display locale, which an application can
/// set with [Locale#setDefault(Locale.Category, Locale)]; other languages fall back to English.
final class Texts {

    private Texts() {
    }

    static String get(String key, Object... arguments) {
        Locale locale = Locale.getDefault(Locale.Category.DISPLAY);
        String pattern = ResourceBundle.getBundle("de.schladebeck.fx.grid.texts", locale).getString(key);
        return arguments.length == 0 ? pattern : new MessageFormat(pattern, locale).format(arguments);
    }
}
