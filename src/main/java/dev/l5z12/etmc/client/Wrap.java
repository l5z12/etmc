package dev.l5z12.etmc.client;

//? if yarn {
import net.minecraft.client.font.TextRenderer;
//?} else {
/*import net.minecraft.client.gui.Font;*/
//?}

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Word-wraps plain text to a pixel width, so long messages (an EasyTier error, a diagnosis) stay on
 * screen instead of running off both edges as one centred line. Only measuring a string differs
 * across versions; the wrapping itself is plain Java.
 */
public final class Wrap {

    private Wrap() {}

    //? if yarn {
    public static int width(TextRenderer font, String s) {
        //? if >=1.16 {
        return font.getWidth(s);
        //?} else {
        /*return font.getStringWidth(s);*/
        //?}
    }

    public static List<String> lines(TextRenderer font, String text, int maxWidth) {
        return wrap(s -> width(font, s), text, maxWidth);
    }
    //?} else {
    /*public static int width(Font font, String s) {
        return font.width(s);
    }

    public static List<String> lines(Font font, String text, int maxWidth) {
        return wrap(s -> width(font, s), text, maxWidth);
    }*/
    //?}

    /** Greedy word wrap; a single word wider than {@code maxWidth} is broken by characters. */
    static List<String> wrap(ToIntFunction<String> width, String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (word.isEmpty()) continue;
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (width.applyAsInt(candidate) <= maxWidth) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                // The word alone may still be too wide (a long URL or link): break it by characters.
                String rest = word;
                while (width.applyAsInt(rest) > maxWidth && rest.length() > 1) {
                    int n = rest.length() - 1;
                    while (n > 1 && width.applyAsInt(rest.substring(0, n)) > maxWidth) n--;
                    out.add(rest.substring(0, n));
                    rest = rest.substring(n);
                }
                line.append(rest);
            }
            out.add(line.toString());
        }
        return out;
    }
}
