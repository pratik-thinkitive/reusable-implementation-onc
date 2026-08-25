package com.onc.G2.dto;

import lombok.Data;

/** Outcome of a patient's access request. The controller turns this into a status code. */
@Data
public class AccessRequestResult {

    public enum Outcome {
        /** Stored and awaiting admin review. */
        CREATED,
        /** An existing request blocks this one. */
        DUPLICATE
    }

    private Outcome outcome;

    /** The new request for CREATED, the blocking one for DUPLICATE. */
    private String requestId;

    /** Only set for DUPLICATE. */
    private String message;

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
