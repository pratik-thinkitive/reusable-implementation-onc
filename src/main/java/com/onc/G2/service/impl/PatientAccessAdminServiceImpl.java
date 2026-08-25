package com.onc.G2.service.impl;

import com.onc.G2.dto.AccessDashboardResponse;
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
 *
 * <p>Nothing here catches to build a response. A refused decision leaves as an
 * {@link com.onc.common.exception.AppException} carrying its own status, and anything unexpected
 * reaches the global handler, which reports it without echoing the cause.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatientAccessAdminServiceImpl implements PatientAccessAdminService {

    private final PatientAccessRequestService patientAccessRequestService;
    private final PatientAccessDataService patientAccessDataService;

    /** Transactional so a half-finished grant cannot leave the status and counters disagreeing. */
    @Override
    @Transactional
    public PatientAccessRequestDto grantAccess(Long requestId) {
        log.info("Granting access for request: {} ", requestId);

        PatientAccessRequestDto request = patientAccessRequestService.grantAccess(requestId);

        ReportingPeriod period = new ReportingPeriod(
                request.getReportingPeriodStart(), request.getReportingPeriodEnd());

        // Safety net - returns the existing row when there already is one.
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

        // When they asked is when the encounter actually happened.
        patientAccessDataService.updateDenominator(
                request.getPatientFhirId(),
                period.start(),
                period.end(),
                orNow(request.getRequestedAt(), request.getAccessGrantedAt()));

        patientAccessDataService.updateNumerator(
                request.getPatientFhirId(),
                period.start(),
                period.end(),
                true,
                orNow(request.getAccessGrantedAt()));

        return request;
    }

    /** Transactional for the same reason as {@link #grantAccess(Long)}. */
    @Override
    @Transactional
    public PatientAccessRequestDto revokeAccess(Long requestId) {
        log.info("Revoking access for request: {}", requestId);

        PatientAccessRequestDto request = patientAccessRequestService.revokeAccess(requestId);

        patientAccessDataService.decrementNumerator(
                request.getPatientFhirId(),
                request.getReportingPeriodStart(),
                request.getReportingPeriodEnd());

        return request;
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
        dashboard.setPercentage(
                totalDenominator > 0 ? (double) totalNumerator / totalDenominator * 100.0 : 0.0);
        return dashboard;
    }

    private Instant orNow(Instant... candidates) {
        for (Instant candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return Instant.now();
    }
}
