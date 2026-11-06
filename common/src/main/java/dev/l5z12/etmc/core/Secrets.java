package dev.l5z12.etmc.core;

import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Network secrets: how a random one is made, and how the Paper config gets one. */
public final class Secrets {

    /** What the bundled Paper {@code config.yml} ships with. */
    private static final String DEFAULT = "change-me";
    /** No look-alike characters, because people read these out and retype them. */
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final int LENGTH = 12;
    private static final Pattern SECRET_LINE = Pattern.compile("^secret:.*$", Pattern.MULTILINE);
    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {}

    public static String random() {
        StringBuilder b = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return b.toString();
    }

    /** True if nobody chose this secret, so anyone who knows the defaults could join with it. */
    public static boolean isUnset(String secret) {
        return secret == null || secret.isBlank() || DEFAULT.equals(secret);
    }

    /**
     * Returns the config text with its top-level {@code secret} set. It edits the text rather than
     * saving through Bukkit, which drops the file's comments on older supported versions.
     */
    public static String withSecret(String yaml, String secret) {
        String line = "secret: \"" + secret + "\"";
        Matcher m = SECRET_LINE.matcher(yaml);
        if (m.find()) return m.replaceFirst(Matcher.quoteReplacement(line));
        return yaml + (yaml.isEmpty() || yaml.endsWith("\n") ? "" : "\n") + line + "\n";
    }
}
