package dev.nulli0n.vbot.addon;

import dev.nulli0n.vbot.addon.api.AddonBotEvent;
import dev.nulli0n.vbot.addon.api.AddonBotService;
import dev.nulli0n.vbot.addon.api.AddonBotSnapshot;
import dev.nulli0n.vbot.addon.api.AddonContext;
import dev.nulli0n.vbot.addon.api.AddonLogger;
import dev.nulli0n.vbot.addon.api.AddonServerSwitchResult;
import dev.nulli0n.vbot.addon.api.Bots4VeloAddon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AddonLoaderTest {
    private static final String SERVICE = "META-INF/services/" + Bots4VeloAddon.class.getName();

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void resetProvider() {
        TestAddon.loaded = 0;
        TestAddon.unloaded = 0;
        TestAddon.context = null;
        FailingAddon.unloaded = 0;
    }

    @Test
    void loadsAndUnloadsProviderFromBundleDirectory() throws Exception {
        Path bundle = temporaryDirectory.resolve("addons/rental");
        serviceJar(bundle.resolve("rental.jar"), TestAddon.class.getName());
        RecordingLogger logger = new RecordingLogger();

        AddonLoader loader = new AddonLoader(temporaryDirectory, new EmptyBotService(), logger, "3.1.0");
        assertThat(loader.loadAll()).isEqualTo(1);
        assertThat(loader.loadedIds()).containsExactly("test-addon");
        assertThat(TestAddon.loaded).isEqualTo(1);
        assertThat(TestAddon.context.dataDirectory()).isEqualTo(bundle);
        assertThat(TestAddon.context.coreVersion()).isEqualTo("3.1.0");

        loader.close();
        assertThat(TestAddon.unloaded).isEqualTo(1);
        assertThat(loader.loadedIds()).isEmpty();
    }

    @Test
    void isolatesFailingProviderAndLoadsNextProvider() throws Exception {
        Path bundle = temporaryDirectory.resolve("addons/mixed");
        serviceJar(bundle.resolve("mixed.jar"), FailingAddon.class.getName(), TestAddon.class.getName());
        RecordingLogger logger = new RecordingLogger();

        try (AddonLoader loader = new AddonLoader(
            temporaryDirectory, new EmptyBotService(), logger, "3.1.0")) {
            assertThat(loader.loadAll()).isEqualTo(1);
            assertThat(loader.loadedIds()).containsExactly("test-addon");
        }

        assertThat(FailingAddon.unloaded).isEqualTo(1);
        assertThat(logger.errors).hasSize(1);
    }

    private static void serviceJar(Path jar, String... providers) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(SERVICE));
            output.write(String.join("\n", providers).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    public static final class TestAddon implements Bots4VeloAddon {
        static int loaded;
        static int unloaded;
        static AddonContext context;

        @Override
        public String id() {
            return "test-addon";
        }

        @Override
        public void onLoad(AddonContext supplied) {
            loaded++;
            context = supplied;
        }

        @Override
        public void onUnload() {
            unloaded++;
        }
    }

    public static final class FailingAddon implements Bots4VeloAddon {
        static int unloaded;

        @Override
        public String id() {
            return "failing-addon";
        }

        @Override
        public void onLoad(AddonContext context) {
            throw new IllegalStateException("expected test failure");
        }

        @Override
        public void onUnload() {
            unloaded++;
        }
    }

    private static final class RecordingLogger implements AddonLogger {
        private final java.util.ArrayList<Throwable> errors = new java.util.ArrayList<>();

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message, Throwable failure) {
            errors.add(failure);
        }
    }

    private static final class EmptyBotService implements AddonBotService {
        @Override
        public List<AddonBotSnapshot> bots() {
            return List.of();
        }

        @Override
        public Optional<AddonBotSnapshot> bot(String id) {
            return Optional.empty();
        }

        @Override
        public boolean start(String id) {
            return false;
        }

        @Override
        public boolean stop(String id) {
            return false;
        }

        @Override
        public boolean reconnect(String id) {
            return false;
        }

        @Override
        public Optional<String> currentServer(String id) {
            return Optional.empty();
        }

        @Override
        public CompletionStage<AddonServerSwitchResult> switchServer(String id, String server) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean isPlayerOnline(UUID playerId) {
            return false;
        }

        @Override
        public boolean sendPlayerMessage(UUID playerId, String message) {
            return false;
        }

        @Override
        public void addEventListener(Consumer<AddonBotEvent> listener) {
        }

        @Override
        public void removeEventListener(Consumer<AddonBotEvent> listener) {
        }
    }
}
