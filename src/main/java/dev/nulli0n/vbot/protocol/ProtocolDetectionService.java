package dev.nulli0n.vbot.protocol;

import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;

import java.io.IOException;

@FunctionalInterface
public interface ProtocolDetectionService {
    DetectedProtocol detect(BotDefinition definition, ProxyEndpoint proxyEndpoint) throws IOException;
}
