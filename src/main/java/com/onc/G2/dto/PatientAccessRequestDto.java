package com.onc.G2.dto;

import com.onc.G2.entity.PatientAccessRequest;
import lombok.Data;

import java.time.LocalDateTime;

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
    private PatientAccessRequest.RequestType requestType;
    private PatientAccessRequest.RequestStatus status;
    private LocalDateTime requestedAt;
    private LocalDateTime accessGrantedAt;
    private LocalDateTime accessRevokedAt;
    private LocalDateTime reportingPeriodStart;
    private LocalDateTime reportingPeriodEnd;
    private Boolean isFirstEncounter;

    private Boolean duplicateRequest = false;
    private String duplicateMessage;
}
