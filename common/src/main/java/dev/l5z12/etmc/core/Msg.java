package dev.l5z12.etmc.core;

import java.util.IllegalFormatException;
import java.util.Locale;

/**
 * Player-facing text, translatable. Every message is written as
 * {@code Msg.tr("etmc.<area>.<name>", "English text with %s", arg)}: the English stays next to the code
 * that uses it, and is what you get wherever no translation applies (the Paper plugin, a key a
 * language file lacks). The client installs Minecraft's language lookup as the {@link Translator}, so
 * there the player's chosen game language picks the text from {@code assets/etmc/lang/*.json}.
 *
 * <p>Placeholders are {@link String#format} ones ({@code %s}, {@code %1$s}), as in Minecraft's own
 * language files. {@code en_us.json} is generated from these calls, and a test keeps every language
 * file in step with them (see {@code LangFilesTest}).
 */
public final class Msg {

    /** Returns the translation of {@code key} with {@code args} filled in, or null if it has none. */
    @FunctionalInterface
    public interface Translator {
        String translate(String key, Object... args);
    }

    private static volatile Translator translator;

    private Msg() {}

    public static void setTranslator(Translator t) {
        translator = t;
    }

    public static String tr(String key, String english, Object... args) {
        Translator t = translator;
        if (t != null) {
            try {
                String s = t.translate(key, args);
                if (s != null && !s.equals(key)) return s;
            } catch (RuntimeException ignored) {
                // A broken translation (bad placeholder in a resource pack) falls back to English.
            }
        }
        if (args.length == 0) return english;
        try {
            return String.format(Locale.ROOT, english, args);
        } catch (IllegalFormatException e) {
            return english;
        }
    }
}
