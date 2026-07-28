package dev.nulli0n.vbot.api;

import java.util.Optional;

/** Access point available after Bots4Velo has completed proxy initialization. */
public final class Bots4VeloApiProvider {
    private static volatile Bots4VeloApi api;

    private Bots4VeloApiProvider() {
    }

    public static Optional<Bots4VeloApi> get() {
        return Optional.ofNullable(api);
    }

    public static void register(Bots4VeloApi replacement) {
        api = replacement;
    }

    public static void clear(Bots4VeloApi candidate) {
        if (api == candidate) {
            api = null;
        }
    }
}
