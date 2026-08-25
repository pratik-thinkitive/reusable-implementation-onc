package com.onc.G2.dto;

import lombok.Data;

/**
 * A patient's identity and who they bill under, read from the EHR.
 * Fields are null when the lookup fails - callers get an empty instance, not an exception.
 */
@Data
public class PatientAttribution {

    private String firstName;
    private String lastName;
    private Integer organisationId;
    private String providerId;
    private String tinId;
}
