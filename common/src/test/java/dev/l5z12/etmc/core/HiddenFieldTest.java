package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/** The shape of the game's key manager: an interface field, and a getter another mod has changed. */
class HiddenFieldTest {

    interface Keys {}

    interface Other {}

    private static final Keys REAL = new Keys() {};
    private static final Keys EMPTY = new Keys() {};

    private static final class Game {
        private static final Keys STATIC = new Keys() {};
        private final Other other = new Other() {};
        private final Keys keys;

        Game(Keys keys) {
            this.keys = keys;
        }
    }

    private static final class GameWithAClassField {
        private final Object keys = REAL;
    }

    @Test
    void itFindsWhatTheGetterHides() {
        assertSame(REAL, HiddenField.read(new Game(REAL), EMPTY));
    }

    @Test
    void withNothingHiddenItIsTheSameThing() {
        assertSame(REAL, HiddenField.read(new Game(REAL), REAL));
    }

    @Test
    void anEmptyFieldLeavesWhatTheGetterSaid() {
        assertSame(EMPTY, HiddenField.read(new Game(null), EMPTY));
    }

    @Test
    void withoutAMatchingFieldItKeepsWhatTheGetterSaid() {
        assertSame(EMPTY, HiddenField.read(new GameWithAClassField(), EMPTY));
    }
}
