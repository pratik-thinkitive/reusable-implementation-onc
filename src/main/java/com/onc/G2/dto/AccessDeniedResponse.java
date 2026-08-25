package com.onc.G2.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

/** Sent when a patient asks for health information they have not been granted access to. */
@Data
@JsonPropertyOrder({"success", "message", "accessGranted", "requestType"})
public class AccessDeniedResponse {

    private boolean success;
    private String message;
    private boolean accessGranted;

    /** The kind of access they need to request, as its enum name. */
    private String requestType;
}
