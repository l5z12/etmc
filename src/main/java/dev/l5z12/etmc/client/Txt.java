package dev.l5z12.etmc.client;

import dev.l5z12.etmc.core.Msg;

//? if yarn && >=1.16 {
import net.minecraft.text.MutableText;
//?} else if !yarn {
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

    /**
     * Minecraft's own lookup in the game's current language, installed as etmc's {@link Msg.Translator}.
     * A key the language lacks comes back as the key itself, which {@link Msg#tr} treats as "none".
     */
    public static String i18n(String key, Object... args) {
        //? if yarn {
        return net.minecraft.client.resource.language.I18n.translate(key, args);
        //?} else {
        /*return net.minecraft.client.resources.language.I18n.get(key, args);*/
        //?}
    }

    //? if yarn && >=1.19 {
    public static MutableText literal(String s) {
        return net.minecraft.text.Text.literal(s);
    }

    // Msg.tr as a text component: the player's language, else the inline English.
    public static MutableText tr(String key, String english, Object... args) {
        return literal(Msg.tr(key, english, args));
    }

    public static MutableText translatable(String key, Object... args) {
        return net.minecraft.text.Text.translatable(key, args);
    }
    //?} else if yarn && >=1.16 {
    /*public static MutableText literal(String s) {
        return new LiteralText(s);
    }

    // Msg.tr as a text component: the player's language, else the inline English.
    public static MutableText tr(String key, String english, Object... args) {
        return literal(Msg.tr(key, english, args));
    }

    public static MutableText translatable(String key, Object... args) {
        return new TranslatableText(key, args);
    }
    *///?} else if yarn {
    /*public static net.minecraft.text.Text literal(String s) {
        return new LiteralText(s);
    }

    // Msg.tr as a text component: the player's language, else the inline English.
    public static net.minecraft.text.Text tr(String key, String english, Object... args) {
        return literal(Msg.tr(key, english, args));
    }

    public static net.minecraft.text.Text translatable(String key, Object... args) {
        return new TranslatableText(key, args);
    }
    *///?} else if >=1.19 {
    /*public static MutableComponent literal(String s) {
        return Component.literal(s);
    }

    // Msg.tr as a text component: the player's language, else the inline English.
    public static MutableComponent tr(String key, String english, Object... args) {
        return literal(Msg.tr(key, english, args));
    }

    public static MutableComponent translatable(String key, Object... args) {
        return Component.translatable(key, args);
    }
    *///?} else {
    /*public static MutableComponent literal(String s) {
        return new TextComponent(s);
    }

    // Msg.tr as a text component: the player's language, else the inline English.
    public static MutableComponent tr(String key, String english, Object... args) {
        return literal(Msg.tr(key, english, args));
    }

    public static MutableComponent translatable(String key, Object... args) {
        return new TranslatableComponent(key, args);
    }
    *///?}
}
