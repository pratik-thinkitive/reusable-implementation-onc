package com.onc.G2.dto;

import lombok.Data;

/**
 * The identifying details looked up from the EHR for one patient: who they are, and which
 * organisation, provider and TIN they belong to.
 *
 * <p>Replaces the private {@code PatientDetails} class that used to live inside
 * {@code G2Controller}. Being a normal top-level class, it can now be returned by a service and
 * asserted on in tests.
 *
 * <p>Every field may be {@code null}: when the EHR lookup fails, an empty instance is returned
 * rather than an exception, which is the behaviour the endpoints have always had.
 */
@Data
public class PatientAttribution {

    private String firstName;
    private String lastName;
    private Integer organisationId;
    private String providerId;
    private String tinId;
}
