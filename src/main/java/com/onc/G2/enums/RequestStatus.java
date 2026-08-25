package com.onc.G2.enums;

/**
 * Lifecycle state of an access request: PENDING, then granted or revoked once an admin acts.
 *
 * <p>Persisted by name and referenced as a string literal in PatientAccessRequestRepository's
 * JPQL, so renaming a constant needs a data migration too.
 */
public enum RequestStatus {
    PENDING,
    ACCESS_GRANTED,
    ACCESS_REVOKED
}
