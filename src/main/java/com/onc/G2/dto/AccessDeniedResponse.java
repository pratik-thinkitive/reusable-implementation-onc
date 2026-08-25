package com.onc.G2.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

/**
 * Told to a patient who asks for their health information before access is granted.
 *
 * <p>Field names and values match the HashMap this replaced. The order is pinned so it is
 * deterministic rather than hash-ordered.
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
