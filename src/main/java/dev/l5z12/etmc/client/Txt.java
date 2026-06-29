package dev.l5z12.etmc.client;

//? if yarn {
import net.minecraft.text.MutableText;
//?} else {
/*import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;*/
//?}
//? if yarn && <1.19 {
/*import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;*/
//?} else if <1.19 {
/*import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;*/
//?}

/**
 * Tiny text-factory facade so screens/commands are version- AND loader-agnostic. Fabric (yarn): 1.19+
 * use the {@code Text.literal}/{@code translatable} statics, 1.16–1.18 the {@code LiteralText}/
 * {@code TranslatableText} ctors. NeoForge/Forge (mojmap): {@code Component.literal}/{@code translatable}.
 * Callers always go through {@code Txt}; only this file carries the split.
 */
public final class Txt {

    private Txt() {}

    //? if yarn && >=1.19 {
    public static MutableText literal(String s) {
        return net.minecraft.text.Text.literal(s);
    }

    public static MutableText translatable(String key, Object... args) {
        return net.minecraft.text.Text.translatable(key, args);
    }
    //?} else if yarn {
    /*public static MutableText literal(String s) {
        return new LiteralText(s);
    }

    public static MutableText translatable(String key, Object... args) {
        return new TranslatableText(key, args);
    }
    *///?} else if >=1.19 {
    /*public static MutableComponent literal(String s) {
        return Component.literal(s);
    }

    public static MutableComponent translatable(String key, Object... args) {
        return Component.translatable(key, args);
    }
    *///?} else {
    /*public static MutableComponent literal(String s) {
        return new TextComponent(s);
    }

    public static MutableComponent translatable(String key, Object... args) {
        return new TranslatableComponent(key, args);
    }
    *///?}
}
