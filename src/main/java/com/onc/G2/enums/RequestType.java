package com.onc.G2.enums;

/**
 * Category of patient data a patient access request covers.
 *
 * <p>Persisted by name via {@code @Enumerated(EnumType.STRING)}, so the constant names are
 * part of the stored schema and the JSON contract - rename only alongside a data migration.
 */
public enum RequestType {
    MEDICAL_DETAILS_ACCESS,
    PERSONAL_DETAILS_ACCESS,
}
