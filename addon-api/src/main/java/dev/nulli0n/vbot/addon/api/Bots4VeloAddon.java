package dev.nulli0n.vbot.addon.api;

/** Entry point discovered from META-INF/services in an addon JAR. */
public interface Bots4VeloAddon {
    String id();

    default String version() {
        return "unspecified";
    }

    void onLoad(AddonContext context) throws Exception;

    default void onUnload() throws Exception {
    }
}
