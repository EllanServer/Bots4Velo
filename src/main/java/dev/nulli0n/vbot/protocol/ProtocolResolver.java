package dev.nulli0n.vbot.protocol;

import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.protocol.ProtocolSelection;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

public final class ProtocolResolver {
    private final ProxyEndpoint endpoint;
    private final BotDefinition definition;
    private final ProtocolDetectionService detector;
    private final AtomicReference<ProtocolVersion> cached = new AtomicReference<>();
    private volatile String source = "unresolved";

    public ProtocolResolver(ProxyEndpoint endpoint, BotDefinition definition,
                            ProtocolDetectionService detector) {
        this.endpoint = endpoint;
        this.definition = definition;
        this.detector = detector;
        ProtocolSelection configured = definition.protocolOverride() == null
            ? endpoint.protocol() : definition.protocolOverride();
        if (!configured.automatic()) {
            cached.set(configured.fixedVersion());
            source = definition.protocolOverride() == null ? "manual-config" : "manual-bot-config";
        }
    }

    public ProtocolResolver(ProxyEndpoint endpoint, BotDefinition definition) {
        this(endpoint, definition, (ignored, proxy) -> new StatusProtocolDetector().detect(proxy));
    }

    public ProtocolVersion resolve() throws IOException {
        ProtocolVersion existing = cached.get();
        if (existing != null) {
            return existing;
        }
        DetectedProtocol result = detector.detect(definition, endpoint);
        ProtocolVersion detected = result.requireSupported();
        cached.compareAndSet(null, detected);
        source = result.source();
        return cached.get();
    }

    public String source() {
        return source;
    }

    public void invalidateAutomaticDetection() {
        ProtocolSelection configured = definition.protocolOverride() == null
            ? endpoint.protocol() : definition.protocolOverride();
        if (configured.automatic()) {
            cached.set(null);
            source = "unresolved";
        }
    }
}
