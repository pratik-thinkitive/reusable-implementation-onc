package com.onc.G2.service.impl;

import com.onc.G2.dto.AccessDashboardResponse;
import com.onc.G2.dto.AccessRequestResponse;
import com.onc.G2.dto.PatientAccessDataDto;
import com.onc.G2.dto.PatientAccessRequestDto;
import com.onc.G2.model.ReportingPeriod;
import com.onc.G2.service.PatientAccessAdminService;
import com.onc.G2.service.PatientAccessDataService;
import com.onc.G2.service.PatientAccessRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Carries out administrator decisions and reports measure performance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatientAccessAdminServiceImpl implements PatientAccessAdminService {

    private final PatientAccessRequestService patientAccessRequestService;
    private final PatientAccessDataService patientAccessDataService;

    /**
     * {@inheritDoc}
     *
     * <p>Marked {@code @Transactional} so the status change and the three counter updates either
     * all happen or none do. Previously these were four separate calls made from the controller,
     * each committing on its own, so a failure part-way through could leave a request marked as
     * granted while the counters still said otherwise.
     */
    @Override
    @Transactional
    public AccessRequestResponse grantAccess(Long requestId) {
        log.info("Granting access for request: {} ", requestId);

        AccessRequestResponse response = patientAccessRequestService.grantAccess(requestId);
        if (!response.isSuccess()) {
            return response;
        }

        PatientAccessRequestDto request = patientAccessRequestService.getAccessRequestById(requestId);
        if (request == null) {
            return response;
        }

        ReportingPeriod period = new ReportingPeriod(
                request.getReportingPeriodStart(), request.getReportingPeriodEnd());

        // Should already exist from when the patient made the request; this is a safety net and
        // returns the existing row when there is one.
        patientAccessDataService.initializePatientData(
                request.getPatientFhirId(),
                request.getPatientId(),
                request.getFirstName(),
                request.getLastName(),
                request.getOrganisationId(),
                request.getProviderId(),
                request.getTinId(),
                period.start(),
                period.end());

        // Granting access implies the patient had an encounter. Prefer the moment they asked,
        // since that is when the encounter actually happened.
        patientAccessDataService.updateDenominator(
                request.getPatientFhirId(),
                period.start(),
                period.end(),
                orNow(request.getRequestedAt(), request.getAccessGrantedAt()));

        // The numerator is timed to the approval itself.
        patientAccessDataService.updateNumerator(
                request.getPatientFhirId(),
                period.start(),
                period.end(),
                true,
                orNow(request.getAccessGrantedAt()));

        return response;
    }

    /** {@inheritDoc} <p>Transactional for the same reason as {@link #grantAccess(Long)}. */
    @Override
    @Transactional
    public AccessRequestResponse revokeAccess(Long requestId) {
        log.info("Revoking access for request: {}", requestId);

        AccessRequestResponse response = patientAccessRequestService.revokeAccess(requestId);
        if (!response.isSuccess()) {
            return response;
        }

        PatientAccessRequestDto request = patientAccessRequestService.getAccessRequestById(requestId);
        if (request == null) {
            return response;
        }

        patientAccessDataService.decrementNumerator(
                request.getPatientFhirId(),
                request.getReportingPeriodStart(),
                request.getReportingPeriodEnd());

        return response;
    }

    @Override
    public AccessDashboardResponse getProviderDashboard(Integer organisationId,
                                                        String providerId,
                                                        String tinId,
                                                        ReportingPeriod period) {

        List<PatientAccessDataDto> patients = patientAccessDataService
                .getAccessGrantedPatientsFiltered(organisationId, providerId, tinId,
                        period.start(), period.end());

        return summarise(null, patients, period);
    }

    @Override
    public AccessDashboardResponse getGroupDashboard(String tinId, ReportingPeriod period) {
        List<PatientAccessDataDto> patients = patientAccessDataService
                .getAccessGrantedPatientsForGroup(tinId, period.start(), period.end());

        return summarise(tinId, patients, period);
    }

    /** Adds up the per-patient rows into the totals a dashboard shows. */
    private AccessDashboardResponse summarise(String groupId,
                                              List<PatientAccessDataDto> patients,
                                              ReportingPeriod period) {

        int totalDenominator = patients.stream().mapToInt(PatientAccessDataDto::getDenominatorCount).sum();
        int totalNumerator = patients.stream().mapToInt(PatientAccessDataDto::getNumeratorCount).sum();

        AccessDashboardResponse dashboard = new AccessDashboardResponse();
        dashboard.setGroupId(groupId);
        dashboard.setPatientsWithAccess(patients);
        dashboard.setReportingPeriodStart(period.start());
        dashboard.setReportingPeriodEnd(period.end());
        dashboard.setTotalNumerator(totalNumerator);
        dashboard.setTotalDenominator(totalDenominator);
        // Guarded against a zero denominator, which is what an empty dashboard looks like.
        dashboard.setPercentage(
                totalDenominator > 0 ? (double) totalNumerator / totalDenominator * 100.0 : 0.0);
        return dashboard;
    }

    /**
     * Returns the first timestamp that is present, or the current time if none are.
     *
     * <p>Replaces the nested conditional the controller used, which read as "use this timestamp,
     * or the next best one, or failing everything, right now".
     */
    private Instant orNow(Instant... candidates) {
        for (Instant candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return Instant.now();
    }
}
