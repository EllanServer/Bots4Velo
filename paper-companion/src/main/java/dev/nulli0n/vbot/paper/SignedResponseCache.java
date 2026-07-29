package dev.nulli0n.vbot.paper;

import dev.nulli0n.vbot.backend.protocol.ControlRequest;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class SignedResponseCache {
    enum Match {
        MISS,
        PENDING,
        READY,
        CONFLICT
    }

    static final class Lookup {
        private final Match match;
        private final byte[] response;

        private Lookup(Match match, byte[] response) {
            this.match = match;
            this.response = response == null ? null : response.clone();
        }

        Match match() {
            return match;
        }

        byte[] response() {
            return response == null ? null : response.clone();
        }
    }

    private final long retentionMillis;
    private final int maximumEntries;
    private final Map<UUID, Entry> entries = new LinkedHashMap<UUID, Entry>();

    SignedResponseCache(long retentionMillis, int maximumEntries) {
        if (retentionMillis < 1 || maximumEntries < 1) {
            throw new IllegalArgumentException("Invalid signed-response cache settings");
        }
        this.retentionMillis = retentionMillis;
        this.maximumEntries = maximumEntries;
    }

    synchronized Lookup lookup(ControlRequest request, byte[] signedRequest, long nowMillis) {
        prune(nowMillis);
        Entry entry = entries.get(request.requestId());
        if (entry == null) {
            return new Lookup(Match.MISS, null);
        }
        if (!entry.matches(request, signedRequest)) {
            return new Lookup(Match.CONFLICT, null);
        }
        if (entry.response == null) {
            return new Lookup(Match.PENDING, null);
        }
        return new Lookup(Match.READY, entry.response);
    }

    synchronized boolean begin(ControlRequest request, byte[] signedRequest, long nowMillis) {
        prune(nowMillis);
        Entry existing = entries.get(request.requestId());
        if (existing != null) {
            return existing.matches(request, signedRequest);
        }
        while (entries.size() >= maximumEntries) {
            Iterator<UUID> iterator = entries.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
        entries.put(request.requestId(), new Entry(request, fingerprint(signedRequest),
            saturatingAdd(nowMillis, retentionMillis), null));
        return true;
    }

    synchronized boolean complete(ControlRequest request, byte[] signedResponse, long nowMillis) {
        prune(nowMillis);
        Entry entry = entries.get(request.requestId());
        if (entry == null || !entry.matchesIdentity(request)) {
            return false;
        }
        entry.response = signedResponse.clone();
        entry.expiresAt = saturatingAdd(nowMillis, retentionMillis);
        return true;
    }

    synchronized int size() {
        return entries.size();
    }

    private void prune(long nowMillis) {
        Iterator<Map.Entry<UUID, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            long expiresAt = iterator.next().getValue().expiresAt;
            if (expiresAt != Long.MAX_VALUE && expiresAt <= nowMillis) {
                iterator.remove();
            }
        }
    }

    private static long saturatingAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static byte[] fingerprint(byte[] signedRequest) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(signedRequest);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class Entry {
        private final UUID targetUuid;
        private final byte[] nonce;
        private final dev.nulli0n.vbot.backend.protocol.BackendOperation operation;
        private final byte[] requestFingerprint;
        private long expiresAt;
        private byte[] response;

        private Entry(ControlRequest request, byte[] requestFingerprint, long expiresAt, byte[] response) {
            this.targetUuid = request.targetUuid();
            this.nonce = request.nonce();
            this.operation = request.operation();
            this.requestFingerprint = requestFingerprint;
            this.expiresAt = expiresAt;
            this.response = response;
        }

        private boolean matches(ControlRequest request, byte[] signedRequest) {
            return matchesIdentity(request)
                && MessageDigest.isEqual(requestFingerprint, fingerprint(signedRequest));
        }

        private boolean matchesIdentity(ControlRequest request) {
            return targetUuid.equals(request.targetUuid()) && operation == request.operation()
                && Arrays.equals(nonce, request.nonce());
        }
    }
}
