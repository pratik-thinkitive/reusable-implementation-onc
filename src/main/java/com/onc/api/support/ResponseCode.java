package com.onc.api.support;

import org.springframework.http.HttpStatus;

/**
 * Stable wire codes returned in {@code ApiResponse.code}. Each constant owns its HTTP status, so
 * the code-to-status mapping exists in exactly one place.
 *
 * <p>Clients branch on the name, so these are part of the published contract - add constants
 * freely, but never rename or repurpose one.
 */
public enum ResponseCode {

    OK(HttpStatus.OK),
    ENTITY(HttpStatus.OK),
    CREATED(HttpStatus.CREATED),
    ACCEPTED(HttpStatus.ACCEPTED),
    UPDATED(HttpStatus.OK),

    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    CONFLICT(HttpStatus.CONFLICT),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS),

    /** The patient has not been granted access to the health information they asked for. */
    PATIENT_ACCESS_DENIED(HttpStatus.FORBIDDEN),

    /** An access request is blocked by an existing granted, revoked or same-encounter request. */
    DUPLICATE_REQUEST(HttpStatus.CONFLICT),

    /** A grant or revoke was refused because the request is not in the required status. */
    STATUS_TRANSITION_BLOCKED(HttpStatus.CONFLICT),

    /** The upstream EHR provider API answered with an error or could not be reached. */
    UPSTREAM_UNAVAILABLE(HttpStatus.BAD_GATEWAY),

    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ResponseCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean isSuccess() {
        return !status.isError();
    }
}
