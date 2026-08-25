package com.onc.G2.dto;

import com.onc.G2.enums.RequestStatus;
import com.onc.G2.enums.RequestType;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

@Data
public class PatientAccessRequestDto {

    private Long id;
    private String firstName;
    private String lastName;
    private String patientFhirId;
    private String patientId;
    private Integer organisationId;
    private String providerId;
    private String tinId;
    private String encounterId;
    private RequestType requestType;
    private RequestStatus status;
    private Instant requestedAt;
    private Instant accessGrantedAt;
    private Instant accessRevokedAt;
    private LocalDate reportingPeriodStart;
    private LocalDate reportingPeriodEnd;
    private Boolean isFirstEncounter;

    private Boolean duplicateRequest = false;
    private String duplicateMessage;
}
