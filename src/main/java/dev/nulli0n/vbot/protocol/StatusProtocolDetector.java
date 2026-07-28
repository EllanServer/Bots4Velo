package dev.nulli0n.vbot.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Performs the protocol-stable Minecraft Status handshake. The status packet
 * framing has been compatible since 1.7, so it can select an adapter before a
 * version-specific login client is created.
 */
public final class StatusProtocolDetector {
    private static final int MAX_STATUS_JSON_BYTES = 1_048_576;

    public DetectedProtocol detect(ProxyEndpoint endpoint) throws IOException {
        int timeout = endpoint.protocolDetectionTimeoutMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.address(), endpoint.port()), timeout);
            socket.setSoTimeout(timeout);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());

            writeHandshake(output, endpoint.virtualHost(), endpoint.virtualPort());
            writeVarInt(output, 1);
            writeVarInt(output, 0);
            output.flush();

            int packetLength = readVarInt(input);
            if (packetLength <= 0 || packetLength > MAX_STATUS_JSON_BYTES + 10) {
                throw new IOException("Invalid Status response packet length: " + packetLength);
            }
            int packetId = readVarInt(input);
            if (packetId != 0) {
                throw new IOException("Unexpected Status response packet id: " + packetId);
            }
            int jsonLength = readVarInt(input);
            if (jsonLength < 2 || jsonLength > MAX_STATUS_JSON_BYTES) {
                throw new IOException("Invalid Status JSON length: " + jsonLength);
            }
            byte[] jsonBytes = input.readNBytes(jsonLength);
            if (jsonBytes.length != jsonLength) {
                throw new EOFException("Incomplete Status JSON response");
            }
            return parseStatusJson(new String(jsonBytes, StandardCharsets.UTF_8));
        }
    }

    static DetectedProtocol parseStatusJson(String json) throws IOException {
        try {
            JsonObject version = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("version");
            if (version == null || !version.has("protocol")) {
                throw new IOException("Status response does not contain version.protocol");
            }
            String name = version.has("name") ? version.get("name").getAsString() : "unknown";
            return new DetectedProtocol(name, version.get("protocol").getAsInt(), "proxy-status");
        }
        catch (RuntimeException exception) {
            throw new IOException("Malformed Minecraft Status JSON", exception);
        }
    }

    private static void writeHandshake(DataOutputStream output, String virtualHost, int virtualPort) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        writeVarInt(packet, 0);
        writeVarInt(packet, -1);
        writeString(packet, virtualHost);
        packet.writeShort(virtualPort);
        writeVarInt(packet, 1);
        packet.flush();
        byte[] bytes = buffer.toByteArray();
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    static void writeVarInt(DataOutputStream output, int value) throws IOException {
        int remaining = value;
        do {
            int current = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                current |= 0x80;
            }
            output.writeByte(current);
        }
        while (remaining != 0);
    }

    static int readVarInt(DataInputStream input) throws IOException {
        int value = 0;
        int position = 0;
        while (position < 32) {
            int current = input.readUnsignedByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        throw new IOException("VarInt is too large");
    }
}
