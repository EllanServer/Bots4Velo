package dev.nulli0n.vbot.backend.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void tamperingIsRejectedBeforeBodyIsTrusted() {
        byte[] frame = ProtocolCodec.encodeRequest(
            request(System.currentTimeMillis(), BackendPolicy.unchanged()), SECRET);
        frame[20] ^= 0x40;

        ProtocolException exception = assertThrows(ProtocolException.class,
            () -> ProtocolCodec.decodeRequest(frame, SECRET));

        assertEquals(BackendStatus.UNAUTHORIZED, exception.status());
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
        byte[] nonce = new byte[ControlRequest.NONCE_BYTES];
        for (int index = 0; index < nonce.length; index++) {
            nonce[index] = (byte) index;
        }
        return new ControlRequest(UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"), timestamp, nonce,
            BackendOperation.APPLY_POLICY, policy);
    }
}
