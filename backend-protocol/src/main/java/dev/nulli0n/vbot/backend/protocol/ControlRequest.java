package dev.nulli0n.vbot.backend.protocol;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class ControlRequest {
    public static final int NONCE_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UUID requestId;
    private final UUID targetUuid;
    private final long timestampMillis;
    private final byte[] nonce;
    private final BackendOperation operation;
    private final BackendPolicy policy;

    public ControlRequest(UUID requestId, UUID targetUuid, long timestampMillis, byte[] nonce,
                          BackendOperation operation, BackendPolicy policy) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.targetUuid = Objects.requireNonNull(targetUuid, "targetUuid");
        this.timestampMillis = timestampMillis;
        this.nonce = Objects.requireNonNull(nonce, "nonce").clone();
        if (this.nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("nonce must contain exactly " + NONCE_BYTES + " bytes");
        }
        this.operation = Objects.requireNonNull(operation, "operation");
        if (operation == BackendOperation.APPLY_POLICY && policy == null) {
            throw new IllegalArgumentException("APPLY_POLICY requires a policy");
        }
        if (operation != BackendOperation.APPLY_POLICY && policy != null) {
            throw new IllegalArgumentException(operation + " does not accept a policy");
        }
        this.policy = policy;
    }

    public static ControlRequest create(UUID targetUuid, BackendOperation operation, BackendPolicy policy) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return new ControlRequest(UUID.randomUUID(), targetUuid, System.currentTimeMillis(), nonce, operation, policy);
    }

    public UUID requestId() {
        return requestId;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    public byte[] nonce() {
        return nonce.clone();
    }

    public BackendOperation operation() {
        return operation;
    }

    public BackendPolicy policy() {
        return policy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ControlRequest)) {
            return false;
        }
        ControlRequest that = (ControlRequest) other;
        return timestampMillis == that.timestampMillis && requestId.equals(that.requestId)
            && targetUuid.equals(that.targetUuid) && Arrays.equals(nonce, that.nonce)
            && operation == that.operation && Objects.equals(policy, that.policy);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(requestId, targetUuid, timestampMillis, operation, policy);
        return 31 * result + Arrays.hashCode(nonce);
    }
}
