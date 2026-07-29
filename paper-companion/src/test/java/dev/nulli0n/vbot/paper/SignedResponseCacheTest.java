package dev.nulli0n.vbot.paper;

import dev.nulli0n.vbot.backend.protocol.ActualState;
import dev.nulli0n.vbot.backend.protocol.BackendOperation;
import dev.nulli0n.vbot.backend.protocol.BackendStatus;
import dev.nulli0n.vbot.backend.protocol.ControlRequest;
import dev.nulli0n.vbot.backend.protocol.ControlResponse;
import dev.nulli0n.vbot.backend.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignedResponseCacheTest {
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void lostAcknowledgementRetryReplaysTheExactSignedResponse() throws Exception {
        long now = 1_000_000L;
        SignedResponseCache cache = new SignedResponseCache(30_000L, 10);
        ControlRequest request = request(UUID.randomUUID(), (byte) 1);
        byte[] signedRequest = ProtocolCodec.encodeRequest(request, SECRET);
        byte[] originalAcknowledgement = ProtocolCodec.encodeResponse(
            ControlResponse.reply(request, BackendStatus.OK, "applied", ActualState.absent()), SECRET);

        assertEquals(SignedResponseCache.Match.MISS,
            cache.lookup(request, signedRequest, now).match());
        assertTrue(cache.begin(request, signedRequest, now));
        assertEquals(SignedResponseCache.Match.PENDING,
            cache.lookup(request, signedRequest, now).match());
        assertTrue(cache.complete(request, originalAcknowledgement, now));

        SignedResponseCache.Lookup retry = cache.lookup(request, signedRequest, now + 5_000L);
        assertEquals(SignedResponseCache.Match.READY, retry.match());
        assertArrayEquals(originalAcknowledgement, retry.response());
        assertTrue(ProtocolCodec.decodeResponse(retry.response(), SECRET).successful());

        byte[] callerCopy = retry.response();
        callerCopy[0] ^= 1;
        assertArrayEquals(originalAcknowledgement,
            cache.lookup(request, signedRequest, now + 5_001L).response());
    }

    @Test
    void conflictingRequestIdNeverReceivesTheCachedAcknowledgement() {
        long now = 1_000_000L;
        SignedResponseCache cache = new SignedResponseCache(30_000L, 10);
        UUID requestId = UUID.randomUUID();
        ControlRequest original = request(requestId, (byte) 1);
        byte[] originalFrame = ProtocolCodec.encodeRequest(original, SECRET);
        cache.begin(original, originalFrame, now);
        cache.complete(original, new byte[]{1, 2, 3}, now);

        ControlRequest differentNonce = request(requestId, (byte) 2);
        assertEquals(SignedResponseCache.Match.CONFLICT,
            cache.lookup(differentNonce, ProtocolCodec.encodeRequest(differentNonce, SECRET), now).match());

        ControlRequest changedTimestamp = new ControlRequest(original.requestId(), original.targetUuid(),
            original.timestampMillis() + 1L, original.nonce(), original.operation(), null);
        byte[] changedSignedBody = ProtocolCodec.encodeRequest(changedTimestamp, SECRET);
        assertEquals(SignedResponseCache.Match.CONFLICT,
            cache.lookup(changedTimestamp, changedSignedBody, now).match());
    }

    @Test
    void cacheIsBoundedAndEntriesExpire() {
        long now = 1_000_000L;
        SignedResponseCache cache = new SignedResponseCache(100L, 1);
        ControlRequest first = request(UUID.randomUUID(), (byte) 1);
        byte[] firstFrame = ProtocolCodec.encodeRequest(first, SECRET);
        cache.begin(first, firstFrame, now);
        cache.complete(first, new byte[]{1}, now);

        ControlRequest second = request(UUID.randomUUID(), (byte) 2);
        byte[] secondFrame = ProtocolCodec.encodeRequest(second, SECRET);
        cache.begin(second, secondFrame, now);

        assertEquals(1, cache.size());
        assertEquals(SignedResponseCache.Match.MISS, cache.lookup(first, firstFrame, now).match());
        assertEquals(SignedResponseCache.Match.MISS, cache.lookup(second, secondFrame, now + 100L).match());
    }

    private static ControlRequest request(UUID requestId, byte nonceMarker) {
        byte[] nonce = new byte[ControlRequest.NONCE_BYTES];
        nonce[0] = nonceMarker;
        return new ControlRequest(requestId,
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), 1_000_000L, nonce,
            BackendOperation.PROBE, null);
    }
}
