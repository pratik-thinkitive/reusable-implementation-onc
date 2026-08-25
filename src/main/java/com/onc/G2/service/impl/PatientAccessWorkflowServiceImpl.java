package com.onc.G2.service.impl;

import com.onc.G2.dto.AccessRequestResult;
import com.onc.G2.dto.PatientAccessRequestDto;
import com.onc.G2.dto.PatientAttribution;
import com.onc.G2.enums.RequestType;
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
 * Not {@code @Transactional} on purpose: requesting access calls the EHR over HTTP, and holding
 * a transaction open across that would pin a connection for the whole round trip.
 *
 * <p>Nothing here catches to build a response. An unexpected failure reaches the global handler,
 * which reports a 500 without echoing the cause to the caller.
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
    }

    @Override
    public AccessRequestResult requestAccess(String patientFhirId,
                                             RequestType requestType,
                                             String encounterId,
                                             String providerId,
                                             String tinId,
                                             ReportingPeriod period) {

        // Name and organisation come from the EHR; provider and TIN come from the caller's
        // parameters below, so the looked-up ones go unused.
        PatientAttribution attribution = patientAttributionService.lookup(patientFhirId);

        PatientAccessRequestDto requestDto = patientAccessRequestService.createAccessRequest(
                patientFhirId,
                extractPatientId(patientFhirId),
                attribution.getFirstName(),
                attribution.getLastName(),
                attribution.getOrganisationId(),
                providerId,
                tinId,
                requestType,
                encounterId,
                null, // isFirstEncounter is worked out later, when the encounter date is set
                period.start(),
                period.end());

        if (Boolean.TRUE.equals(requestDto.getDuplicateRequest())) {
            return AccessRequestResult.duplicate(
                    requestDto.getId().toString(), requestDto.getDuplicateMessage());
        }

        // Idempotent: one row per patient per period.
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

        // The request happens during an encounter, so it counts towards the denominator.
        patientAccessDataService.updateDenominator(
                patientFhirId, period.start(), period.end(), Instant.now());

        return AccessRequestResult.created(requestDto.getId().toString());
    }

    /**
     * Falls back to the whole input when there is no dash. EHRDataService has a lookalike that
     * returns null instead, so the two are not interchangeable.
     */
    private String extractPatientId(String patientFhirId) {
        if (patientFhirId != null && patientFhirId.contains("-")) {
            return patientFhirId.split("-")[1];
        }
        return patientFhirId;
    }
}
