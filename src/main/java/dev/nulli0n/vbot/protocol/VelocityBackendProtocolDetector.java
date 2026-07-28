package dev.nulli0n.vbot.protocol;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Detects the protocol advertised by the bot's intended backend. Velocity may
 * advertise its own newest supported protocol, which is not necessarily the
 * protocol accepted by that backend.
 */
public final class VelocityBackendProtocolDetector implements ProtocolDetectionService {
    private final ProxyServer proxyServer;
    private final StatusProtocolDetector proxyStatusDetector = new StatusProtocolDetector();

    public VelocityBackendProtocolDetector(ProxyServer proxyServer) {
        this.proxyServer = proxyServer;
    }

    @Override
    public DetectedProtocol detect(BotDefinition definition, ProxyEndpoint endpoint) throws IOException {
        String serverName = definition.protocolDetectionServer().isBlank()
            ? definition.targetServer() : definition.protocolDetectionServer();
        if (serverName.isBlank()) {
            return proxyStatusDetector.detect(endpoint);
        }

        RegisteredServer server = proxyServer.getServer(serverName)
            .orElseThrow(() -> new IOException("Unknown protocol detection backend: " + serverName));
        try {
            ServerPing ping = server.ping().get(endpoint.protocolDetectionTimeoutMillis(), TimeUnit.MILLISECONDS);
            ServerPing.Version version = ping.getVersion();
            return new DetectedProtocol(version.getName(), version.getProtocol(), "backend:" + serverName);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while pinging protocol detection backend " + serverName, exception);
        }
        catch (ExecutionException | TimeoutException exception) {
            throw new IOException("Could not ping protocol detection backend " + serverName, exception);
        }
    }
}
