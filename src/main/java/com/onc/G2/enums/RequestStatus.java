package com.onc.G2.enums;

/**
 * Lifecycle state of a patient access request: PENDING on creation, then ACCESS_GRANTED or
 * ACCESS_REVOKED once an admin acts on it.
 *
 * <p>Persisted by name via {@code @Enumerated(EnumType.STRING)}, and referenced as a string
 * literal in the JPQL of {@code PatientAccessRequestRepository}, so the constant names are
 * part of the stored schema and the JSON contract - rename only alongside a data migration.
 */
public enum RequestStatus {
    PENDING,
    ACCESS_GRANTED,
    ACCESS_REVOKED
}
