package dev.l5z12.etmc.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Finds what an object really holds when a mod has changed what its getter answers. It goes by type,
 * because a field's name differs between game versions and mappings.
 */
public final class HiddenField {

    private HiddenField() {}

    /**
     * The value of {@code owner}'s field of the interface type that {@code shown} implements.
     *
     * @param shown what the getter answered, which this falls back to if the field can't be read
     */
    @SuppressWarnings("unchecked")
    public static <T> T read(Object owner, T shown) {
        try {
            for (Field field : owner.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!field.getType().isInterface() || !field.getType().isInstance(shown)) continue;
                field.setAccessible(true);
                Object value = field.get(owner);
                if (value != null) return (T) value;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            EtmcLog.warn("couldn't read " + owner.getClass().getSimpleName() + "'s field directly: " + e);
        }
        return shown;
    }
}
