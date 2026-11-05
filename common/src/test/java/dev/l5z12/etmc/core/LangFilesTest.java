package dev.l5z12.etmc.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps the language files in step with the code. Every player-facing string is written as
 * {@code Msg.tr("etmc.key", "English", args)} (or {@code Txt.tr}); this reads all of those calls and
 * checks that
 * <ul>
 *   <li>{@code en_us.json} is exactly the code's English (run with {@code -Petmc.writeLang=true} to
 *       regenerate it rather than fail);</li>
 *   <li>every other language has every key, no stale ones, and the same placeholders, so a
 *       translation can't drop or invent an argument.</li>
 * </ul>
 */
class LangFilesTest {

    /** Keys used through Minecraft's own translation (keybinds), not through Msg.tr. */
    private static final Map<String, String> NON_CODE_KEYS = Map.of(
            "key.categories.etmc", "etmc",
            "key.etmc.open_menu", "Open etmc menu");

    private static final Pattern CALL = Pattern.compile(
            "(?:Msg|Txt)\\.tr\\(\\s*\"(etmc\\.[a-z0-9_.]+)\"\\s*,\\s*((?:\"(?:\\\\.|[^\"\\\\])*\"\\s*\\+?\\s*)+)");
    private static final Pattern LITERAL = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[sd]");

    @Test
    void languageFilesMatchTheCode() throws IOException {
        Path root = repoRoot();
        Map<String, String> english = new TreeMap<>(NON_CODE_KEYS);
        List<String> conflicts = new ArrayList<>();
        for (Path dir : List.of(root.resolve("src/main/java"), root.resolve("common/src/main/java"))) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    Matcher m = CALL.matcher(Files.readString(f, StandardCharsets.UTF_8));
                    while (m.find()) {
                        String text = joinLiterals(m.group(2));
                        String prev = english.put(m.group(1), text);
                        if (prev != null && !prev.equals(text)) {
                            conflicts.add(m.group(1) + ": \"" + prev + "\" vs \"" + text + "\" (" + f.getFileName() + ")");
                        }
                    }
                }
            }
        }
        assertTrue(conflicts.isEmpty(), "one key, two English texts:\n" + String.join("\n", conflicts));
        assertTrue(english.size() > 100, "found only " + english.size() + " keys; did the Msg.tr pattern change?");

        Path langDir = root.resolve("src/main/resources/assets/etmc/lang");
        Path enUs = langDir.resolve("en_us.json");
        if (Boolean.getBoolean("etmc.writeLang")) {
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            Files.writeString(enUs, gson.toJson(english) + "\n", StandardCharsets.UTF_8);
        }
        assertEquals(english, read(enUs), "en_us.json is out of date: run the Paper tests with -Petmc.writeLang=true");

        try (Stream<Path> files = Files.list(langDir)) {
            for (Path lang : (Iterable<Path>) files.filter(p -> !p.equals(enUs))::iterator) {
                Map<String, String> tr = read(lang);
                List<String> problems = new ArrayList<>();
                for (Map.Entry<String, String> e : english.entrySet()) {
                    String t = tr.get(e.getKey());
                    if (t == null) {
                        problems.add("missing " + e.getKey());
                    } else if (!placeholders(t).equals(placeholders(e.getValue()))) {
                        problems.add(e.getKey() + ": placeholders " + placeholders(t) + ", English has "
                                + placeholders(e.getValue()));
                    }
                }
                for (String k : tr.keySet()) {
                    if (!english.containsKey(k)) problems.add("stale " + k);
                }
                if (!problems.isEmpty()) fail(lang.getFileName() + ":\n  " + String.join("\n  ", problems));
            }
        }
    }

    /**
     * The arguments a text uses, as sorted "index+type" entries: {@code "%s %s"} and {@code "%2$s %1$s"}
     * both use arguments 1 and 2, so a translation may reorder them but not drop or add one.
     */
    private static List<String> placeholders(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(s);
        int next = 1;
        while (m.find()) {
            String p = m.group();
            int dollar = p.indexOf('$');
            int index = dollar < 0 ? next++ : Integer.parseInt(p.substring(1, dollar));
            out.add(index + p.substring(p.length() - 1));
        }
        out.sort(null);
        return out;
    }

    private static String joinLiterals(String concatenation) {
        StringBuilder sb = new StringBuilder();
        Matcher m = LITERAL.matcher(concatenation);
        while (m.find()) sb.append(unescape(m.group(1)));
        return sb.toString();
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> sb.append(n); // \" \' \\
            }
        }
        return sb.toString();
    }

    private static Map<String, String> read(Path p) throws IOException {
        Map<String, String> m = new Gson().fromJson(Files.readString(p, StandardCharsets.UTF_8),
                new TypeToken<Map<String, String>>() {}.getType());
        return new TreeMap<>(m);
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle.kts"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("can't find the repo root from " + Paths.get("").toAbsolutePath());
        return p;
    }
}
