package com.onc.G2.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

/**
 * Outcome of a patient's access request, and the payload the endpoint returns for it.
 *
 * <p>{@code success} and {@code message} are not fields here - those belong to the response
 * envelope. What survives is the identity of the request the caller needs to follow up on.
 */
@Data
@JsonPropertyOrder({"requestId", "status"})
public class AccessRequestResult {

    public enum Outcome {
        /** Stored and awaiting admin review. */
        CREATED,
        /** An existing request blocks this one. */
        DUPLICATE
    }

    @JsonIgnore
    private Outcome outcome;

    /** The new request for CREATED, the blocking one for DUPLICATE. */
    private String requestId;

    /** Why the request was blocked. Only set for DUPLICATE; carried in the envelope's message. */
    @JsonIgnore
    private String message;

    public String getStatus() {
        return outcome == Outcome.DUPLICATE ? "DUPLICATE" : "PENDING";
    }

    public static AccessRequestResult created(String requestId) {
        AccessRequestResult result = new AccessRequestResult();
        result.setOutcome(Outcome.CREATED);
        result.setRequestId(requestId);
        return result;
    }

    public static AccessRequestResult duplicate(String requestId, String message) {
        AccessRequestResult result = new AccessRequestResult();
        result.setOutcome(Outcome.DUPLICATE);
        result.setRequestId(requestId);
        result.setMessage(message);
        return result;
    }

    public boolean isDuplicate() {
        return outcome == Outcome.DUPLICATE;
    }
}
