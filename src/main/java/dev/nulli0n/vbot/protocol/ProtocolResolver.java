package dev.nulli0n.vbot.protocol;

import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import java.io.IOException;

public final class ProtocolResolver {
    private final ProxyEndpoint endpoint;
    private final BotDefinition definition;
    private final ProtocolDetectionService detector;
    private ProtocolVersion cached;
    private String source = "unresolved";
    /** Guarded by this; retires detector results already in flight. */
    private long detectionEpoch;

    public ProtocolResolver(ProxyEndpoint endpoint, BotDefinition definition,
                            ProtocolDetectionService detector) {
        this.endpoint = endpoint;
        this.definition = definition;
        this.detector = detector;
        ProtocolSelection configured = definition.protocolOverride() == null
            ? endpoint.protocol() : definition.protocolOverride();
        if (!configured.automatic()) {
            cached = configured.fixedVersion();
            source = definition.protocolOverride() == null ? "manual-config" : "manual-bot-config";
        }
    }

    public ProtocolResolver(ProxyEndpoint endpoint, BotDefinition definition) {
        this(endpoint, definition, (ignored, proxy) -> new StatusProtocolDetector().detect(proxy));
    }

    public ProtocolVersion resolve() throws IOException {
        while (true) {
            long attemptEpoch;
            synchronized (this) {
                if (cached != null) {
                    return cached;
                }
                attemptEpoch = detectionEpoch;
            }

            DetectedProtocol result = detector.detect(definition, endpoint);
            synchronized (this) {
                if (attemptEpoch != detectionEpoch) {
                    // invalidateAutomaticDetection() retired this result while
                    // the detector was running. Loop so this caller observes
                    // the new cache or performs a detection in the new epoch.
                    continue;
                }
                ProtocolVersion detected = result.requireSupported();
                if (cached == null) {
                    // Commit version and provenance as one state transition.
                    // A concurrent detector that loses this race must return
                    // the winner without overwriting its source.
                    source = result.source();
                    cached = detected;
                }
                return cached;
            }
        }
    }

    public synchronized String source() {
        return source;
    }

    public synchronized void invalidateAutomaticDetection() {
        ProtocolSelection configured = definition.protocolOverride() == null
            ? endpoint.protocol() : definition.protocolOverride();
        if (configured.automatic()) {
            detectionEpoch++;
            cached = null;
            source = "unresolved";
        }
    }
}
