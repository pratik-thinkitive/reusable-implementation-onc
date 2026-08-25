package com.onc.G2.service.impl;

import com.onc.G2.dto.AccessRequestResult;
import com.onc.G2.dto.PatientAccessRequestDto;
import com.onc.G2.dto.PatientAttribution;
import com.onc.G2.enums.RequestType;
import com.onc.G2.exception.AccessOperationException;
import com.onc.G2.exception.PatientDataAccessException;
import com.onc.G2.model.ReportingPeriod;
import com.onc.G2.service.PatientAccessDataService;
import com.onc.G2.service.PatientAccessRequestService;
import com.onc.G2.service.PatientAccessWorkflowService;
import com.onc.G2.service.PatientAttributionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Coordinates the patient-facing steps of the G2 measure.
 *
 * <p>Deliberately not {@code @Transactional}: requesting access calls the EHR over HTTP, and
 * holding a transaction open across that would pin a connection for the whole round trip.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatientAccessWorkflowServiceImpl implements PatientAccessWorkflowService {

    private final PatientAccessRequestService patientAccessRequestService;
    private final PatientAccessDataService patientAccessDataService;
    private final PatientAttributionService patientAttributionService;

    @Override
    public boolean checkAccessAndRecordView(String patientFhirId) {
        try {
            boolean hasActiveAccess = patientAccessRequestService
                    .hasActiveAccess(patientFhirId, RequestType.MEDICAL_DETAILS_ACCESS);

            if (!hasActiveAccess) {
                log.info("Patient: {} does not have active access. Returning access message.", patientFhirId);
                return false;
            }

            log.info("Patient: {} has active access, fetching medical details", patientFhirId);

            ReportingPeriod period = ReportingPeriod.currentCalendarYear();
            patientAccessDataService.updateNumerator(
                    patientFhirId, period.start(), period.end(), true, Instant.now());

            return true;

        } catch (Exception e) {
            throw new PatientDataAccessException(
                    "Failed to check access for patient " + patientFhirId, e);
        }
    }

    @Override
    public AccessRequestResult requestAccess(String patientFhirId,
                                             String requestType,
                                             String encounterId,
                                             String providerId,
                                             String tinId,
                                             String reportingPeriodStart,
                                             String reportingPeriodEnd) {
        try {
            return createRequest(patientFhirId, requestType, encounterId, providerId, tinId,
                    reportingPeriodStart, reportingPeriodEnd);
        } catch (Exception e) {
            // Names the operation so the caller sees the same message as before.
            throw new AccessOperationException("Error creating access request: " + e.getMessage(), e);
        }
    }

    private AccessRequestResult createRequest(String patientFhirId,
                                              String requestType,
                                              String encounterId,
                                              String providerId,
                                              String tinId,
                                              String reportingPeriodStart,
                                              String reportingPeriodEnd) {

        RequestType type = RequestType.valueOf(requestType.toUpperCase());
        ReportingPeriod period = ReportingPeriod.parse(reportingPeriodStart, reportingPeriodEnd);

        // Name and organisation come from the EHR, but provider and TIN come from the caller's
        // parameters below - the looked-up values go unused. Long-standing, preserved on purpose.
        PatientAttribution attribution = patientAttributionService.lookup(patientFhirId);

        PatientAccessRequestDto requestDto = patientAccessRequestService.createAccessRequest(
                patientFhirId,
                extractPatientId(patientFhirId),
                attribution.getFirstName(),
                attribution.getLastName(),
                attribution.getOrganisationId(),
                providerId,
                tinId,
                type,
                encounterId,
                null, // isFirstEncounter is worked out later, when the encounter date is set
                period.start(),
                period.end());

        if (Boolean.TRUE.equals(requestDto.getDuplicateRequest())) {
            return AccessRequestResult.duplicate(
                    requestDto.getId().toString(), requestDto.getDuplicateMessage());
        }

        // One data row per patient per reporting period; a no-op if it already exists.
        patientAccessDataService.initializePatientData(
                patientFhirId,
                extractPatientId(patientFhirId),
                attribution.getFirstName(),
                attribution.getLastName(),
                attribution.getOrganisationId(),
                providerId,
                tinId,
                period.start(),
                period.end());

        // Requesting access happens during an encounter, so it counts towards the denominator.
        patientAccessDataService.updateDenominator(
                patientFhirId, period.start(), period.end(), Instant.now());

        return AccessRequestResult.created(requestDto.getId().toString());
    }

    /**
     * Pulls the patient id out of a composite {@code organisation-patient} id, falling back to
     * the whole input when there is no dash. EHRDataService's lookalike returns null instead,
     * so the two are not interchangeable.
     */
    private String extractPatientId(String patientFhirId) {
        if (patientFhirId != null && patientFhirId.contains("-")) {
            return patientFhirId.split("-")[1];
        }
        return patientFhirId;
    }
}
