package dev.nulli0n.vbot.api.common;

import java.util.Optional;

/** Access point for the additive convenience API after proxy initialization. */
public final class CommonBotApiProvider {
    private static volatile CommonBotApi api;

    private CommonBotApiProvider() {
    }

    public static Optional<CommonBotApi> get() {
        return Optional.ofNullable(api);
    }

    public static void register(CommonBotApi replacement) {
        api = replacement;
    }

    public static void clear(CommonBotApi candidate) {
        if (api == candidate) {
            api = null;
        }
    }
}
