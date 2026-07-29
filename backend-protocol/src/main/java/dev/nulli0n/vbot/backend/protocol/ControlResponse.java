package dev.nulli0n.vbot.backend.protocol;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class ControlResponse {
    private final UUID requestId;
    private final UUID targetUuid;
    private final long timestampMillis;
    private final byte[] requestNonce;
    private final BackendOperation operation;
    private final BackendStatus status;
    private final String detail;
    private final ActualState actualState;

    public ControlResponse(UUID requestId, UUID targetUuid, long timestampMillis, byte[] requestNonce,
                           BackendOperation operation, BackendStatus status, String detail,
                           ActualState actualState) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.targetUuid = Objects.requireNonNull(targetUuid, "targetUuid");
        this.timestampMillis = timestampMillis;
        this.requestNonce = Objects.requireNonNull(requestNonce, "requestNonce").clone();
        if (this.requestNonce.length != ControlRequest.NONCE_BYTES) {
            throw new IllegalArgumentException("requestNonce must contain exactly "
                + ControlRequest.NONCE_BYTES + " bytes");
        }
        this.operation = Objects.requireNonNull(operation, "operation");
        this.status = Objects.requireNonNull(status, "status");
        this.detail = detail == null ? "" : detail;
        this.actualState = Objects.requireNonNull(actualState, "actualState");
    }

    public static ControlResponse reply(ControlRequest request, BackendStatus status, String detail,
                                        ActualState actualState) {
        return new ControlResponse(request.requestId(), request.targetUuid(), System.currentTimeMillis(),
            request.nonce(), request.operation(), status, detail, actualState);
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

    public byte[] requestNonce() {
        return requestNonce.clone();
    }

    public BackendOperation operation() {
        return operation;
    }

    public BackendStatus status() {
        return status;
    }

    public boolean successful() {
        return status == BackendStatus.OK;
    }

    public String detail() {
        return detail;
    }

    public ActualState actualState() {
        return actualState;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ControlResponse)) {
            return false;
        }
        ControlResponse that = (ControlResponse) other;
        return timestampMillis == that.timestampMillis && requestId.equals(that.requestId)
            && targetUuid.equals(that.targetUuid) && Arrays.equals(requestNonce, that.requestNonce)
            && operation == that.operation && status == that.status && detail.equals(that.detail)
            && actualState.equals(that.actualState);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(requestId, targetUuid, timestampMillis, operation, status, detail, actualState);
        return 31 * result + Arrays.hashCode(requestNonce);
    }
}
