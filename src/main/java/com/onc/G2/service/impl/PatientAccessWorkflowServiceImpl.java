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
 * Coordinates the patient-facing steps of the G2 measure.
 *
 * <p>Note this class is deliberately <b>not</b> {@code @Transactional}. Requesting access starts
 * by calling out to the EHR over HTTP, and holding a database transaction open across a network
 * call would tie up a connection for as long as the remote system takes to answer. The
 * collaborators it calls manage their own transactions, exactly as they did when this logic sat
 * in the controller.
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
                                             String requestType,
                                             String encounterId,
                                             String providerId,
                                             String tinId,
                                             String reportingPeriodStart,
                                             String reportingPeriodEnd) {

        RequestType type = RequestType.valueOf(requestType.toUpperCase());
        ReportingPeriod period = ReportingPeriod.parse(reportingPeriodStart, reportingPeriodEnd);

        // Names and organisation come from the EHR. Provider and TIN, however, are taken from
        // the caller's parameters below - the EHR-derived values are looked up but not used for
        // attribution. That is long-standing behaviour, preserved here on purpose.
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

        // One data row per patient per reporting period; this is a no-op if it already exists.
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

        // Requesting access happens during an encounter, so the encounter counts towards the
        // denominator, timed to now.
        patientAccessDataService.updateDenominator(
                patientFhirId, period.start(), period.end(), Instant.now());

        return AccessRequestResult.created(requestDto.getId().toString());
    }

    /**
     * Pulls the provider-local patient id out of a composite {@code organisation-patient} id.
     *
     * <p>Kept identical to the version that lived in {@code G2Controller}, which falls back to
     * the whole input when there is no dash. {@code EHRDataService} has a similarly named method
     * that returns {@code null} instead - the two are not interchangeable, so this one stays.
     */
    private String extractPatientId(String patientFhirId) {
        if (patientFhirId != null && patientFhirId.contains("-")) {
            return patientFhirId.split("-")[1];
        }
        return patientFhirId;
    }
}
