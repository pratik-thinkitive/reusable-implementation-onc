package com.onc.G2.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

/**
 * Told to a patient who asks for their health information before access has been granted.
 *
 * <p>Replaces a hand-built {@code HashMap}. The field names and values are exactly what that map
 * contained, so the response is unchanged for anyone consuming it.
 *
 * <p>{@code @JsonPropertyOrder} is here to make the key order deterministic. A {@code HashMap}
 * ordered its keys by hash, which was stable but arbitrary; this pins them to the order the
 * original code wrote them in.
 */
@Data
@JsonPropertyOrder({"success", "message", "accessGranted", "requestType"})
public class AccessDeniedResponse {

    private boolean success;
    private String message;
    private boolean accessGranted;

    /** The kind of access the patient needs to request, as its enum name. */
    private String requestType;
}
