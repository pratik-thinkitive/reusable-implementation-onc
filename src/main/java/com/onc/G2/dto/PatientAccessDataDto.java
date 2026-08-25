package com.onc.G2.dto;

import lombok.Data;

import java.time.LocalDateTime;

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
    private LocalDateTime reportingPeriodStart;
    private LocalDateTime reportingPeriodEnd;
    private Integer denominatorCount;
    private Integer numeratorCount;
    private Boolean hasAccessGranted;
    private LocalDateTime accessGrantedDate;
    private LocalDateTime accessRevokedDate;
    private Boolean isNumeratorRecorded;
    private LocalDateTime firstEncounterDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Calculated fields
    private Double percentage;
//    private String patientName;
//    private String providerName;
//    private String organisationName;
}
