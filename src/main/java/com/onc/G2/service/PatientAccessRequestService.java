package com.onc.G2.service;

import com.onc.G2.dto.PatientAccessRequestDto;
import com.onc.G2.enums.RequestType;

import java.time.LocalDate;
import java.util.List;

public interface PatientAccessRequestService {

    /**
     * Creates a request, or returns the existing one that blocks it with {@code duplicateRequest}
     * set - a duplicate is a normal outcome the caller reports, not a failure.
     */
    PatientAccessRequestDto createAccessRequest(String patientFhirId, String patientId, String firstName, String lastName,
                                                Integer organisationId, String providerId, String tinId,
                                                RequestType requestType,
                                                String encounterId, Boolean isFirstEncounter,
                                                LocalDate reportingPeriodStart, LocalDate reportingPeriodEnd);

    /**
     * Moves a pending request to granted.
     *
     * @throws com.onc.common.exception.AppException if the request is unknown or not pending
     */
    PatientAccessRequestDto grantAccess(Long requestId);

    /**
     * Withdraws access from a granted request.
     *
     * @throws com.onc.common.exception.AppException if the request is unknown or not granted
     */
    PatientAccessRequestDto revokeAccess(Long requestId);

    /** Whether the patient currently holds access of this type. */
    boolean hasActiveAccess(String patientFhirId, RequestType requestType);

    List<PatientAccessRequestDto> getPendingRequests(Integer organisationId, String providerId, String tinId);

    List<PatientAccessRequestDto> getGrantedRequests(Integer organisationId, String providerId, String tinId);

    List<PatientAccessRequestDto> getRevokedRequests(Integer organisationId, String providerId, String tinId);

    /** Returns null when there is no such request; the controller turns that into a 404. */
    PatientAccessRequestDto getAccessRequestById(Long requestId);

    List<PatientAccessRequestDto> getPatientAccessRequests(String patientFhirId);
}
