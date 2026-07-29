package dev.nulli0n.vbot.backend.protocol;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolCodecTest {
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void requestRoundTripsEveryPolicyField() throws Exception {
        ControlRequest request = request(System.currentTimeMillis(), new BackendPolicy(
            BackendInvulnerability.ENABLED,
            BackendGameMode.ADVENTURE,
            RespawnPoint.fixed("world_nether", 12.5D, 64.0D, -8.25D, 90.0F, 15.0F)
        ));

        ControlRequest decoded = ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(request, SECRET), SECRET);

        assertEquals(request, decoded);
    }

    @Test
    void extendedRequestRoundTripsEveryPolicyField() throws Exception {
        BackendPolicy policy = new BackendPolicy(BackendInvulnerability.ENABLED,
            BackendGameMode.ADVENTURE,
            RespawnPoint.fixed("world_nether", 12.5D, 64.0D, -8.25D, 90.0F, 15.0F),
            ManagedBoolean.ENABLED, ManagedBoolean.DISABLED, ManagedBoolean.ENABLED,
            ManagedBoolean.DISABLED);
        ControlRequest request = request(System.currentTimeMillis(), BackendOperation.APPLY_POLICY_EXT, policy);

        ControlRequest decoded = ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(request, SECRET), SECRET);

        assertEquals(request, decoded);
    }

    @Test
    void extendedProbeAndRecoverRoundTripWithoutPolicy() throws Exception {
        for (BackendOperation operation : new BackendOperation[] {
            BackendOperation.PROBE_EXT, BackendOperation.RECOVER
        }) {
            ControlRequest request = request(System.currentTimeMillis(), operation, null);
            assertEquals(request,
                ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(request, SECRET), SECRET));
        }
    }

    @Test
    void responseRoundTripsActualStateAndError() throws Exception {
        ControlRequest request = request(System.currentTimeMillis(), BackendPolicy.unchanged());
        ActualState actual = ActualState.present(true, BackendGameMode.CREATIVE,
            RespawnPoint.fixed("world", 1.0D, 70.0D, 2.0D, 0.0F, 0.0F));
        ControlResponse response = new ControlResponse(request.requestId(), request.targetUuid(),
            123456789L, request.nonce(), request.operation(), BackendStatus.APPLY_FAILED,
            "another plugin rejected the change", actual);

        ControlResponse decoded = ProtocolCodec.decodeResponse(
            ProtocolCodec.encodeResponse(response, SECRET), SECRET);

        assertEquals(response, decoded);
        assertTrue(!decoded.successful());
    }

    @Test
    void extendedResponseRoundTripsExtendedActualState() throws Exception {
        ControlRequest request = request(System.currentTimeMillis(), BackendOperation.PROBE_EXT, null);
        ActualState actual = ActualState.presentExtended(true, BackendGameMode.CREATIVE,
            RespawnPoint.fixed("world", 1.0D, 70.0D, 2.0D, 0.0F, 0.0F),
            true, false, true, false);
        ControlResponse response = new ControlResponse(request.requestId(), request.targetUuid(),
            123456789L, request.nonce(), request.operation(), BackendStatus.OK,
            "extended", actual);

        ControlResponse decoded = ProtocolCodec.decodeResponse(
            ProtocolCodec.encodeResponse(response, SECRET), SECRET);

        assertEquals(response, decoded);
        assertTrue(decoded.actualState().extendedPresent());
        assertTrue(decoded.actualState().sleepingIgnored());
        assertFalse(decoded.actualState().affectsSpawning());
        assertTrue(decoded.actualState().pickupItems());
        assertFalse(decoded.actualState().collidable());
    }

    @Test
    void legacyOperationFramesRemainByteForByteCompatible() {
        String[] expectedRequests = {
            "QjRWQwABAQAAADkAESIzRFVmd4iZqrvM3e7//ty6mHZUMhABI0VniavN7wAAAYvP5Wh7AAECAwQFBgcICQoLDA0ODwGU6HiLVt00pdspoY83aT9/SLM6sZDxowymK4vkU3WBWA==",
            "QjRWQwABAQAAAGoAESIzRFVmd4iZqrvM3e7//ty6mHZUMhABI0VniavN7wAAAYvP5Wh7AAECAwQFBgcICQoLDA0ODwIBAwIADHdvcmxkX25ldGhlcj/0AAAAAAAAQFAAAAAAAADAFgAAAAAAAEK0AADBcAAAmwzp7L/njAd4vLjPEILxKV9Muus7lYnAlGq+zCySzlY=",
            "QjRWQwABAQAAADkAESIzRFVmd4iZqrvM3e7//ty6mHZUMhABI0VniavN7wAAAYvP5Wh7AAECAwQFBgcICQoLDA0ODwOBliM8W1BqxxxhSFxpWPnCk88LJoYMSNROXAxIDJRADw=="
        };
        String[] expectedResponses = {
            "QjRWQwABAgAAAHQAESIzRFVmd4iZqrvM3e7//ty6mHZUMhABI0VniavN7wAAAYvP5WjeAAECAwQFBgcICQoLDA0ODwEAAAZnb2xkZW4BAQMCAAx3b3JsZF9uZXRoZXI/9AAAAAAAAEBQAAAAAAAAwBYAAAAAAABCtAAAwXAAAGmiOqjMzcy0QZJS1LS/EVq/b2/jPNAFws5gEVzot+Nd",
            "QjRWQwABAgAAAHQAESIzRFVmd4iZqrvM3e7//ty6mHZUMhABI0VniavN7wAAAYvP5WjeAAECAwQFBgcICQoLDA0ODwIAAAZnb2xkZW4BAQMCAAx3b3JsZF9uZXRoZXI/9AAAAAAAAEBQAAAAAAAAwBYAAAAAAABCtAAAwXAAAMyVrG4h9TiVrFwqFM3T83i6en9RIFGFEQXYp89/JYvF",
            "QjRWQwABAgAAAHQAESIzRFVmd4iZqrvM3e7//ty6mHZUMhABI0VniavN7wAAAYvP5WjeAAECAwQFBgcICQoLDA0ODwMAAAZnb2xkZW4BAQMCAAx3b3JsZF9uZXRoZXI/9AAAAAAAAEBQAAAAAAAAwBYAAAAAAABCtAAAwXAAAA01Tht8MqvS0uwhonN64iyNoVfSTHDGOp7ueYO3qmcU"
        };
        BackendOperation[] operations = {
            BackendOperation.PROBE, BackendOperation.APPLY_POLICY, BackendOperation.RESPAWN
        };
        for (int index = 0; index < operations.length; index++) {
            ControlRequest request = goldenRequest(operations[index]);
            assertArrayEquals(Base64.getDecoder().decode(expectedRequests[index]),
                ProtocolCodec.encodeRequest(request, SECRET), operations[index] + " request changed");
            ControlResponse response = new ControlResponse(request.requestId(), request.targetUuid(),
                1700000000222L, request.nonce(), operations[index], BackendStatus.OK, "golden",
                goldenActualState());
            assertArrayEquals(Base64.getDecoder().decode(expectedResponses[index]),
                ProtocolCodec.encodeResponse(response, SECRET), operations[index] + " response changed");
        }
    }

    @Test
    void tamperingIsRejectedBeforeBodyIsTrusted() {
        byte[] frame = ProtocolCodec.encodeRequest(
            request(System.currentTimeMillis(), BackendPolicy.unchanged()), SECRET);
        frame[20] ^= 0x40;

        ProtocolException exception = assertThrows(ProtocolException.class,
            () -> ProtocolCodec.decodeRequest(frame, SECRET));

        assertEquals(BackendStatus.UNAUTHORIZED, exception.status());
    }

    @Test
    void extendedFrameTamperingIsRejectedBeforeBodyIsTrusted() {
        BackendPolicy policy = new BackendPolicy(BackendInvulnerability.ENABLED,
            BackendGameMode.SURVIVAL, RespawnPoint.unchanged(), ManagedBoolean.ENABLED,
            ManagedBoolean.DISABLED, ManagedBoolean.ENABLED, ManagedBoolean.DISABLED);
        byte[] frame = ProtocolCodec.encodeRequest(
            request(System.currentTimeMillis(), BackendOperation.APPLY_POLICY_EXT, policy), SECRET);
        frame[frame.length - 36] ^= 0x01;

        ProtocolException exception = assertThrows(ProtocolException.class,
            () -> ProtocolCodec.decodeRequest(frame, SECRET));

        assertEquals(BackendStatus.UNAUTHORIZED, exception.status());
    }

    @Test
    void invalidRequestExtensionVersionIsRejectedAfterAuthentication() throws Exception {
        byte[] frame = ProtocolCodec.encodeRequest(
            request(System.currentTimeMillis(), BackendOperation.PROBE_EXT, null), SECRET);
        int extensionOffset = 11 + 16 + 16 + 8 + ControlRequest.NONCE_BYTES + 1;
        frame[extensionOffset] = 2;
        resign(frame);

        ProtocolException exception = assertThrows(ProtocolException.class,
            () -> ProtocolCodec.decodeRequest(frame, SECRET));

        assertEquals(BackendStatus.VERSION_MISMATCH, exception.status());
    }

    @Test
    void invalidResponseExtensionVersionIsRejectedAfterAuthentication() throws Exception {
        ControlRequest request = request(System.currentTimeMillis(), BackendOperation.PROBE_EXT, null);
        ControlResponse response = new ControlResponse(request.requestId(), request.targetUuid(),
            123456789L, request.nonce(), request.operation(), BackendStatus.APPLY_FAILED, "",
            ActualState.absent());
        byte[] frame = ProtocolCodec.encodeResponse(response, SECRET);
        frame[frame.length - 32 - 2] = 2;
        resign(frame);

        ProtocolException exception = assertThrows(ProtocolException.class,
            () -> ProtocolCodec.decodeResponse(frame, SECRET));

        assertEquals(BackendStatus.VERSION_MISMATCH, exception.status());
    }

    @Test
    void requestAndResponseKindsCannotBeConfused() {
        ControlRequest request = request(System.currentTimeMillis(), BackendPolicy.unchanged());
        byte[] frame = ProtocolCodec.encodeRequest(request, SECRET);

        ProtocolException exception = assertThrows(ProtocolException.class,
            () -> ProtocolCodec.decodeResponse(frame, SECRET));

        assertEquals(BackendStatus.BAD_REQUEST, exception.status());
    }

    private static ControlRequest request(long timestamp, BackendPolicy policy) {
        return request(timestamp, BackendOperation.APPLY_POLICY, policy);
    }

    private static ControlRequest request(long timestamp, BackendOperation operation, BackendPolicy policy) {
        byte[] nonce = new byte[ControlRequest.NONCE_BYTES];
        for (int index = 0; index < nonce.length; index++) {
            nonce[index] = (byte) index;
        }
        return new ControlRequest(UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"), timestamp, nonce,
            operation, policy);
    }

    private static ControlRequest goldenRequest(BackendOperation operation) {
        byte[] nonce = new byte[ControlRequest.NONCE_BYTES];
        for (int index = 0; index < nonce.length; index++) {
            nonce[index] = (byte) index;
        }
        BackendPolicy policy = operation == BackendOperation.APPLY_POLICY
            ? new BackendPolicy(BackendInvulnerability.ENABLED, BackendGameMode.ADVENTURE,
                RespawnPoint.fixed("world_nether", 1.25D, 64.0D, -5.5D, 90.0F, -15.0F))
            : null;
        return new ControlRequest(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
            UUID.fromString("fedcba98-7654-3210-0123-456789abcdef"), 1700000000123L,
            nonce, operation, policy);
    }

    private static ActualState goldenActualState() {
        return ActualState.present(true, BackendGameMode.ADVENTURE,
            RespawnPoint.fixed("world_nether", 1.25D, 64.0D, -5.5D, 90.0F, -15.0F));
    }

    private static void resign(byte[] frame) throws Exception {
        int signedLength = frame.length - 32;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        byte[] signature = mac.doFinal(java.util.Arrays.copyOf(frame, signedLength));
        System.arraycopy(signature, 0, frame, signedLength, signature.length);
    }
}
