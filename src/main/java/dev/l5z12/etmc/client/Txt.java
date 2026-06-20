package dev.l5z12.etmc.client;

import net.minecraft.text.MutableText;
//? if <1.19 {
/*import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;*/
//?}

/**
 * Tiny text-factory facade so the Fabric screens/commands are version-agnostic: 1.19+ build text via
 * the {@code Text.literal}/{@code Text.translatable} statics, while 1.16–1.18 use the concrete
 * {@code LiteralText}/{@code TranslatableText} constructors (the statics didn't exist yet). Only this
 * file carries that split; callers always go through {@code Txt}.
 */
public final class Txt {

    private Txt() {}

    //? if >=1.19 {
    public static MutableText literal(String s) {
        return net.minecraft.text.Text.literal(s);
    }

    public static MutableText translatable(String key, Object... args) {
        return net.minecraft.text.Text.translatable(key, args);
    }
    //?} else {
    /*public static MutableText literal(String s) {
        return new LiteralText(s);
    }

    public static MutableText translatable(String key, Object... args) {
        return new TranslatableText(key, args);
    }
    *///?}
}
