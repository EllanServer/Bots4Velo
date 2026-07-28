package dev.nulli0n.vbot.protocol;

import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StatusProtocolDetectorTest {
    @Test
    void detectsProtocolFromStatusHandshake() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> responder = CompletableFuture.runAsync(() -> respond(server, 776, "26.2"));
            ProxyEndpoint endpoint = new ProxyEndpoint("127.0.0.1", server.getLocalPort(), "localhost",
                server.getLocalPort(), ProtocolSelection.autoDetect(), 2_000);

            DetectedProtocol result = new StatusProtocolDetector().detect(endpoint);

            assertThat(result.protocolId()).isEqualTo(776);
            assertThat(result.advertisedName()).isEqualTo("26.2");
            assertThat(result.requireSupported()).isEqualTo(ProtocolVersion.MINECRAFT_26_2);
            responder.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void parsesPatchVersionWithRequiredProtocol() throws Exception {
        DetectedProtocol result = StatusProtocolDetector.parseStatusJson(
            "{\"version\":{\"name\":\"26.1.2\",\"protocol\":775}}");
        assertThat(result.requireSupported()).isEqualTo(ProtocolVersion.MINECRAFT_26_1_2);
    }

    private static void respond(ServerSocket server, int protocol, String name) {
        try (Socket socket = server.accept()) {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            int handshakeLength = StatusProtocolDetector.readVarInt(input);
            input.readNBytes(handshakeLength);
            int requestLength = StatusProtocolDetector.readVarInt(input);
            input.readNBytes(requestLength);

            byte[] json = ("{\"version\":{\"name\":\"" + name + "\",\"protocol\":" + protocol + "}}")
                .getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream packetBytes = new ByteArrayOutputStream();
            DataOutputStream packet = new DataOutputStream(packetBytes);
            StatusProtocolDetector.writeVarInt(packet, 0);
            StatusProtocolDetector.writeVarInt(packet, json.length);
            packet.write(json);
            packet.flush();

            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            StatusProtocolDetector.writeVarInt(output, packetBytes.size());
            output.write(packetBytes.toByteArray());
            output.flush();
        }
        catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
