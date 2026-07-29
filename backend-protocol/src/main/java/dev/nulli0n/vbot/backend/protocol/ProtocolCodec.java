package dev.nulli0n.vbot.backend.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

public final class ProtocolCodec {
    public static final int VERSION = 1;
    public static final int EXTENSION_VERSION = 1;
    public static final int MAX_FRAME_BYTES = 16 * 1024;

    private static final int MAGIC = 0x42345643; // B4VC
    private static final int KIND_REQUEST = 1;
    private static final int KIND_RESPONSE = 2;
    private static final int HEADER_BYTES = 11;
    private static final int MAC_BYTES = 32;
    private static final int MAX_STRING_BYTES = 2 * 1024;

    private ProtocolCodec() {
    }

    public static byte[] encodeRequest(ControlRequest request, byte[] secret) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writeUuid(output, request.requestId());
            writeUuid(output, request.targetUuid());
            output.writeLong(request.timestampMillis());
            output.write(request.nonce());
            output.writeByte(request.operation().id());
            if (request.operation() == BackendOperation.APPLY_POLICY) {
                writePolicy(output, request.policy());
            }
            else if (isExtended(request.operation())) {
                output.writeByte(EXTENSION_VERSION);
                if (request.operation() == BackendOperation.APPLY_POLICY_EXT) {
                    writeExtendedPolicy(output, request.policy());
                }
            }
            output.flush();
            return signFrame(KIND_REQUEST, bytes.toByteArray(), secret);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Could not encode request", exception);
        }
    }

    public static ControlRequest decodeRequest(byte[] frame, byte[] secret) throws ProtocolException {
        byte[] body = verifyFrame(frame, KIND_REQUEST, secret);
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(body));
            UUID requestId = readUuid(input);
            UUID targetUuid = readUuid(input);
            long timestamp = input.readLong();
            byte[] nonce = new byte[ControlRequest.NONCE_BYTES];
            input.readFully(nonce);
            BackendOperation operation = BackendOperation.fromId(input.readUnsignedByte());
            BackendPolicy policy = null;
            if (operation == BackendOperation.APPLY_POLICY) {
                policy = readPolicy(input);
            }
            else if (isExtended(operation)) {
                requireExtensionVersion(input);
                if (operation == BackendOperation.APPLY_POLICY_EXT) {
                    policy = readExtendedPolicy(input);
                }
            }
            requireEnd(input);
            return new ControlRequest(requestId, targetUuid, timestamp, nonce, operation, policy);
        }
        catch (ProtocolException exception) {
            throw exception;
        }
        catch (EOFException exception) {
            throw new ProtocolException(BackendStatus.BAD_REQUEST, "Truncated request body", exception);
        }
        catch (IOException | IllegalArgumentException exception) {
            throw new ProtocolException(BackendStatus.BAD_REQUEST, "Invalid request body", exception);
        }
    }

    public static byte[] encodeResponse(ControlResponse response, byte[] secret) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writeUuid(output, response.requestId());
            writeUuid(output, response.targetUuid());
            output.writeLong(response.timestampMillis());
            output.write(response.requestNonce());
            output.writeByte(response.operation().id());
            output.writeByte(response.status().id());
            writeString(output, response.detail());
            writeActualState(output, response.actualState());
            if (isExtended(response.operation())) {
                writeExtendedActualState(output, response.actualState());
            }
            output.flush();
            return signFrame(KIND_RESPONSE, bytes.toByteArray(), secret);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Could not encode response", exception);
        }
    }

    public static ControlResponse decodeResponse(byte[] frame, byte[] secret) throws ProtocolException {
        byte[] body = verifyFrame(frame, KIND_RESPONSE, secret);
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(body));
            UUID requestId = readUuid(input);
            UUID targetUuid = readUuid(input);
            long timestamp = input.readLong();
            byte[] requestNonce = new byte[ControlRequest.NONCE_BYTES];
            input.readFully(requestNonce);
            BackendOperation operation = BackendOperation.fromId(input.readUnsignedByte());
            BackendStatus status = BackendStatus.fromId(input.readUnsignedByte());
            String detail = readString(input);
            ActualState actual = readActualState(input);
            if (isExtended(operation)) {
                actual = readExtendedActualState(input, actual);
            }
            requireEnd(input);
            return new ControlResponse(requestId, targetUuid, timestamp, requestNonce,
                operation, status, detail, actual);
        }
        catch (ProtocolException exception) {
            throw exception;
        }
        catch (EOFException exception) {
            throw new ProtocolException(BackendStatus.BAD_REQUEST, "Truncated response body", exception);
        }
        catch (IOException | IllegalArgumentException exception) {
            throw new ProtocolException(BackendStatus.BAD_REQUEST, "Invalid response body", exception);
        }
    }

    private static byte[] signFrame(int kind, byte[] body, byte[] secret) {
        if (body.length + HEADER_BYTES + MAC_BYTES > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Protocol frame exceeds " + MAX_FRAME_BYTES + " bytes");
        }
        validateSecret(secret);
        try {
            ByteArrayOutputStream signedBytes = new ByteArrayOutputStream(HEADER_BYTES + body.length);
            DataOutputStream output = new DataOutputStream(signedBytes);
            output.writeInt(MAGIC);
            output.writeShort(VERSION);
            output.writeByte(kind);
            output.writeInt(body.length);
            output.write(body);
            output.flush();
            byte[] signed = signedBytes.toByteArray();
            byte[] mac = hmac(signed, secret);
            ByteArrayOutputStream frame = new ByteArrayOutputStream(signed.length + mac.length);
            frame.write(signed);
            frame.write(mac);
            return frame.toByteArray();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Could not encode protocol frame", exception);
        }
    }

    private static byte[] verifyFrame(byte[] frame, int expectedKind, byte[] secret) throws ProtocolException {
        validateSecret(secret);
        if (frame == null || frame.length < HEADER_BYTES + MAC_BYTES || frame.length > MAX_FRAME_BYTES) {
            throw new ProtocolException(BackendStatus.BAD_REQUEST, "Invalid protocol frame length");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(frame));
            int magic = input.readInt();
            int version = input.readUnsignedShort();
            int kind = input.readUnsignedByte();
            int bodyLength = input.readInt();
            if (magic != MAGIC || bodyLength < 0
                || bodyLength != frame.length - HEADER_BYTES - MAC_BYTES) {
                throw new ProtocolException(BackendStatus.BAD_REQUEST, "Invalid protocol frame header");
            }
            int signedLength = HEADER_BYTES + bodyLength;
            byte[] expectedMac = hmac(Arrays.copyOf(frame, signedLength), secret);
            byte[] providedMac = Arrays.copyOfRange(frame, signedLength, frame.length);
            if (!MessageDigest.isEqual(expectedMac, providedMac)) {
                throw new ProtocolException(BackendStatus.UNAUTHORIZED, "Protocol signature is invalid");
            }
            if (version != VERSION) {
                throw new ProtocolException(BackendStatus.VERSION_MISMATCH,
                    "Unsupported protocol version " + version);
            }
            if (kind != expectedKind) {
                throw new ProtocolException(BackendStatus.BAD_REQUEST, "Unexpected protocol message kind " + kind);
            }
            return Arrays.copyOfRange(frame, HEADER_BYTES, signedLength);
        }
        catch (ProtocolException exception) {
            throw exception;
        }
        catch (IOException exception) {
            throw new ProtocolException(BackendStatus.BAD_REQUEST, "Could not parse protocol frame", exception);
        }
    }

    private static byte[] hmac(byte[] data, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        }
        catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static void validateSecret(byte[] secret) {
        if (secret == null || secret.length < ProtocolSecrets.MINIMUM_BYTES) {
            throw new IllegalArgumentException("Protocol secret must contain at least "
                + ProtocolSecrets.MINIMUM_BYTES + " bytes");
        }
    }

    private static void writePolicy(DataOutputStream output, BackendPolicy policy) throws IOException {
        output.writeByte(policy.invulnerability().id());
        output.writeByte(policy.gameMode().id());
        writeRespawnPoint(output, policy.respawnPoint());
    }

    private static BackendPolicy readPolicy(DataInputStream input) throws IOException, ProtocolException {
        BackendInvulnerability invulnerability = BackendInvulnerability.fromId(input.readUnsignedByte());
        BackendGameMode gameMode = BackendGameMode.fromId(input.readUnsignedByte());
        return new BackendPolicy(invulnerability, gameMode, readRespawnPoint(input));
    }

    private static void writeExtendedPolicy(DataOutputStream output, BackendPolicy policy) throws IOException {
        writePolicy(output, policy);
        output.writeByte(policy.sleepingIgnored().id());
        output.writeByte(policy.affectsSpawning().id());
        output.writeByte(policy.pickupItems().id());
        output.writeByte(policy.collidable().id());
    }

    private static BackendPolicy readExtendedPolicy(DataInputStream input)
        throws IOException, ProtocolException {
        BackendPolicy base = readPolicy(input);
        return new BackendPolicy(base.invulnerability(), base.gameMode(), base.respawnPoint(),
            ManagedBoolean.fromId(input.readUnsignedByte()),
            ManagedBoolean.fromId(input.readUnsignedByte()),
            ManagedBoolean.fromId(input.readUnsignedByte()),
            ManagedBoolean.fromId(input.readUnsignedByte()));
    }

    private static void writeActualState(DataOutputStream output, ActualState state) throws IOException {
        output.writeBoolean(state.present());
        if (!state.present()) {
            return;
        }
        output.writeBoolean(state.invulnerable());
        output.writeByte(state.gameMode().id());
        writeRespawnPoint(output, state.respawnPoint());
    }

    private static ActualState readActualState(DataInputStream input) throws IOException, ProtocolException {
        if (!input.readBoolean()) {
            return ActualState.absent();
        }
        boolean invulnerable = input.readBoolean();
        BackendGameMode gameMode = BackendGameMode.fromId(input.readUnsignedByte());
        return ActualState.present(invulnerable, gameMode, readRespawnPoint(input));
    }

    private static void writeExtendedActualState(DataOutputStream output, ActualState state) throws IOException {
        output.writeByte(EXTENSION_VERSION);
        output.writeBoolean(state.extendedPresent());
        if (!state.extendedPresent()) {
            return;
        }
        output.writeBoolean(state.sleepingIgnored());
        output.writeBoolean(state.affectsSpawning());
        output.writeBoolean(state.pickupItems());
        output.writeBoolean(state.collidable());
    }

    private static ActualState readExtendedActualState(DataInputStream input, ActualState base)
        throws IOException, ProtocolException {
        requireExtensionVersion(input);
        if (!input.readBoolean()) {
            return base;
        }
        if (!base.present()) {
            throw new ProtocolException(BackendStatus.BAD_REQUEST,
                "Extended actual state requires a present base state");
        }
        return ActualState.presentExtended(base.invulnerable(), base.gameMode(), base.respawnPoint(),
            input.readBoolean(), input.readBoolean(), input.readBoolean(), input.readBoolean());
    }

    private static void requireExtensionVersion(DataInputStream input)
        throws IOException, ProtocolException {
        int extensionVersion = input.readUnsignedByte();
        if (extensionVersion != EXTENSION_VERSION) {
            throw new ProtocolException(BackendStatus.VERSION_MISMATCH,
                "Unsupported protocol extension version " + extensionVersion);
        }
    }

    private static boolean isExtended(BackendOperation operation) {
        return operation == BackendOperation.PROBE_EXT
            || operation == BackendOperation.APPLY_POLICY_EXT
            || operation == BackendOperation.RECOVER;
    }

    private static void writeRespawnPoint(DataOutputStream output, RespawnPoint point) throws IOException {
        output.writeByte(point.mode().id());
        writeString(output, point.world());
        output.writeDouble(point.x());
        output.writeDouble(point.y());
        output.writeDouble(point.z());
        output.writeFloat(point.yaw());
        output.writeFloat(point.pitch());
    }

    private static RespawnPoint readRespawnPoint(DataInputStream input) throws IOException, ProtocolException {
        RespawnMode mode = RespawnMode.fromId(input.readUnsignedByte());
        String world = readString(input);
        double x = input.readDouble();
        double y = input.readDouble();
        double z = input.readDouble();
        float yaw = input.readFloat();
        float pitch = input.readFloat();
        return new RespawnPoint(mode, world, x, y, z, yaw, pitch);
    }

    private static void writeUuid(DataOutputStream output, UUID uuid) throws IOException {
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("Protocol string exceeds " + MAX_STRING_BYTES + " bytes");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException, ProtocolException {
        int length = input.readUnsignedShort();
        if (length > MAX_STRING_BYTES) {
            throw new ProtocolException(BackendStatus.BAD_REQUEST, "Protocol string is too long");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void requireEnd(DataInputStream input) throws IOException, ProtocolException {
        if (input.available() != 0) {
            throw new ProtocolException(BackendStatus.BAD_REQUEST, "Protocol body has trailing data");
        }
    }
}
