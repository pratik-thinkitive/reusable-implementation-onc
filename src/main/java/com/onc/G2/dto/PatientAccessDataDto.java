package com.onc.G2.dto;

import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

@Data
public class PatientAccessDataDto {

    private Long id;
    private String firstName;
    private String lastName;
    private String patientFhirId;
    private String patientId;
    private Integer organisationId;
    private String providerId;
    private String tinId;
    private LocalDate reportingPeriodStart;
    private LocalDate reportingPeriodEnd;
    private Integer denominatorCount;
    private Integer numeratorCount;
    private Boolean hasAccessGranted;
    private Instant accessGrantedDate;
    private Instant accessRevokedDate;
    private Boolean isNumeratorRecorded;
    private Instant firstEncounterDate;
    private Instant createdAt;
    private Instant updatedAt;

    // Calculated fields
    private Double percentage;
//    private String patientName;
//    private String providerName;
//    private String organisationName;
}
