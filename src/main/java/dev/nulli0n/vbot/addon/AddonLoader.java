package dev.nulli0n.vbot.addon;

import dev.nulli0n.vbot.addon.api.AddonBotService;
import dev.nulli0n.vbot.addon.api.AddonContext;
import dev.nulli0n.vbot.addon.api.AddonLogger;
import dev.nulli0n.vbot.addon.api.Bots4VeloAddon;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Loads independent addon bundles from plugins/bots4velo/addons/&lt;name&gt;/. */
public final class AddonLoader implements AutoCloseable {
    private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");

    private final Path addonsDirectory;
    private final AddonBotService bots;
    private final AddonLogger logger;
    private final String coreVersion;
    private final List<LoadedBundle> loaded = new ArrayList<>();
    private final Set<String> addonIds = new HashSet<>();

    public AddonLoader(Path dataDirectory, AddonBotService bots, AddonLogger logger, String coreVersion) {
        this.addonsDirectory = dataDirectory.resolve("addons");
        this.bots = bots;
        this.logger = logger;
        this.coreVersion = coreVersion;
    }

    public synchronized int loadAll() throws IOException {
        Files.createDirectories(addonsDirectory);
        int before = addonIds.size();
        for (Path bundle : childDirectories()) {
            loadBundle(bundle);
        }
        return addonIds.size() - before;
    }

    public synchronized List<String> loadedIds() {
        return addonIds.stream().sorted().toList();
    }

    private List<Path> childDirectories() throws IOException {
        try (Stream<Path> entries = Files.list(addonsDirectory)) {
            return entries.filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        }
    }

    private void loadBundle(Path bundleDirectory) throws IOException {
        List<Path> jars;
        try (Stream<Path> entries = Files.list(bundleDirectory)) {
            jars = entries.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        }
        if (jars.isEmpty()) {
            return;
        }

        URL[] urls = new URL[jars.size()];
        for (int index = 0; index < jars.size(); index++) {
            urls[index] = jars.get(index).toUri().toURL();
        }
        URLClassLoader classLoader = new URLClassLoader(urls, Bots4VeloAddon.class.getClassLoader());
        List<LoadedAddon> bundleAddons = new ArrayList<>();
        try {
            ServiceLoader<Bots4VeloAddon> services = ServiceLoader.load(Bots4VeloAddon.class, classLoader);
            for (ServiceLoader.Provider<Bots4VeloAddon> provider : services.stream().toList()) {
                loadProvider(bundleDirectory, provider, bundleAddons);
            }
        }
        catch (Throwable failure) {
            logger.error("Could not discover addons in " + bundleDirectory.getFileName(), failure);
        }

        if (bundleAddons.isEmpty()) {
            classLoader.close();
            return;
        }
        loaded.add(new LoadedBundle(classLoader, bundleAddons));
    }

    private void loadProvider(Path bundleDirectory, ServiceLoader.Provider<Bots4VeloAddon> provider,
                              List<LoadedAddon> bundleAddons) {
        Bots4VeloAddon addon = null;
        String id = "unknown";
        try {
            addon = provider.get();
            id = normalizeId(addon.id());
            if (!VALID_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("invalid addon id: " + id);
            }
            if (!addonIds.add(id)) {
                throw new IllegalStateException("duplicate addon id: " + id);
            }
            AddonLogger scopedLogger = new PrefixLogger(logger, "[" + id + "] ");
            AddonContext context = new DefaultAddonContext(bots, scopedLogger, bundleDirectory, coreVersion);
            addon.onLoad(context);
            bundleAddons.add(new LoadedAddon(id, addon));
            logger.info("Loaded addon " + id + " " + addon.version());
        }
        catch (Throwable failure) {
            if (!"unknown".equals(id)) {
                addonIds.remove(id);
            }
            logger.error("Could not load addon provider " + provider.type().getName(), failure);
            if (addon != null) {
                try {
                    addon.onUnload();
                }
                catch (Throwable unloadFailure) {
                    logger.error("Could not clean up failed addon " + id, unloadFailure);
                }
            }
        }
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public synchronized void close() {
        for (int bundleIndex = loaded.size() - 1; bundleIndex >= 0; bundleIndex--) {
            LoadedBundle bundle = loaded.get(bundleIndex);
            List<LoadedAddon> addons = bundle.addons();
            for (int addonIndex = addons.size() - 1; addonIndex >= 0; addonIndex--) {
                LoadedAddon loadedAddon = addons.get(addonIndex);
                try {
                    loadedAddon.addon().onUnload();
                }
                catch (Throwable failure) {
                    logger.error("Could not unload addon " + loadedAddon.id(), failure);
                }
                addonIds.remove(loadedAddon.id());
            }
            try {
                bundle.classLoader().close();
            }
            catch (IOException failure) {
                logger.error("Could not close addon class loader", failure);
            }
        }
        loaded.clear();
    }

    private record LoadedAddon(String id, Bots4VeloAddon addon) {
    }

    private record LoadedBundle(URLClassLoader classLoader, List<LoadedAddon> addons) {
    }

    private record DefaultAddonContext(
        AddonBotService bots,
        AddonLogger logger,
        Path dataDirectory,
        String coreVersion
    ) implements AddonContext {
    }

    private record PrefixLogger(AddonLogger delegate, String prefix) implements AddonLogger {
        @Override
        public void info(String message) {
            delegate.info(prefix + message);
        }

        @Override
        public void warn(String message) {
            delegate.warn(prefix + message);
        }

        @Override
        public void error(String message, Throwable failure) {
            delegate.error(prefix + message, failure);
        }
    }
}
