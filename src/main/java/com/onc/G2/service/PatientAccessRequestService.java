package com.onc.G2.service;

import com.onc.G2.dto.AccessRequestResponse;
import com.onc.G2.dto.PatientAccessRequestDto;
import com.onc.G2.entity.PatientAccessRequest;

import java.time.LocalDate;
import java.util.List;

public interface PatientAccessRequestService {

    // Create a new patient access request
    PatientAccessRequestDto createAccessRequest(String patientFhirId, String patientId, String firstName, String lastName,
                                                Integer organisationId, String providerId, String tinId,
                                                PatientAccessRequest.RequestType requestType,
                                                String encounterId, Boolean isFirstEncounter,
                                                LocalDate reportingPeriodStart, LocalDate reportingPeriodEnd);

    // Grant access to patient (directly from pending)
    AccessRequestResponse grantAccess(Long requestId);

    // Revoke access from patient
    AccessRequestResponse revokeAccess(Long requestId);

    // Check if patient has active access for specific request type
    boolean hasActiveAccess(String patientFhirId, PatientAccessRequest.RequestType requestType);

    // Get all pending requests for admin review
    List<PatientAccessRequestDto> getPendingRequests(Integer organisationId, String providerId, String tinId);

    // Get all Granted requests for admin review
    List<PatientAccessRequestDto> getGrantedRequests(Integer organisationId, String providerId, String tinId);

    // Get all Revoked requests for admin review
    List<PatientAccessRequestDto> getRevokedRequests(Integer organisationId, String providerId, String tinId);

    // Get access request by ID
    PatientAccessRequestDto getAccessRequestById(Long requestId);

    // Get access requests for a specific patient
    List<PatientAccessRequestDto> getPatientAccessRequests(String patientFhirId);

}
