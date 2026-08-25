package com.onc.G2.dto;

import lombok.Data;

/**
 * What happened when a patient asked for access.
 *
 * <p>Deliberately says nothing about HTTP. The service decides <em>what happened</em>; the
 * controller decides which status code that maps to. Keeping those apart is the whole point of
 * splitting the layers - if a service returned a {@code ResponseEntity}, the web concern would
 * just have moved rather than gone away.
 */
@Data
public class AccessRequestResult {

    /** The two ways a request can end. */
    public enum Outcome {
        /** A brand new request was stored and is awaiting admin review. */
        CREATED,
        /** The patient already has a request that blocks this one. */
        DUPLICATE
    }

    private Outcome outcome;

    /** Id of the stored request - the new one for CREATED, the blocking one for DUPLICATE. */
    private String requestId;

    /** Set only for DUPLICATE: the patient-facing explanation of why this was blocked. */
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
