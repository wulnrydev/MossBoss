package dev.wulnry.mossboss.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

// Değişiklik: HEX_PATTERN regex + toSection() kaldırıldı.
// LegacyComponentSerializer.character('&').hexColors() &#RRGGBB formatını natively parse eder.
public final class ColorUtil {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private ColorUtil() {}

    public static Component component(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        return SERIALIZER.deserialize(input)
                .decoration(TextDecoration.ITALIC, false);
    }
}
