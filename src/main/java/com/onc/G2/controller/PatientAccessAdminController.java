package com.onc.G2.controller;

import com.onc.G2.dto.AccessDashboardResponse;
import com.onc.G2.dto.PatientAccessDataDto;
import com.onc.G2.dto.PatientAccessRequestDto;
import com.onc.G2.model.ReportingPeriod;
import com.onc.G2.service.PatientAccessAdminService;
import com.onc.G2.service.PatientAccessDataService;
import com.onc.G2.service.PatientAccessRequestService;
import com.onc.api.support.ApiResponse;
import com.onc.api.support.BaseController;
import com.onc.api.support.ResponseCode;
import com.onc.common.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Admin endpoints for access requests and measure performance. */
@RequiredArgsConstructor
@RestController
@RequestMapping("/ehr/admin/patient-access")
@Slf4j
public class PatientAccessAdminController extends BaseController {

    private final PatientAccessRequestService patientAccessRequestService;
    private final PatientAccessDataService patientAccessDataService;
    private final PatientAccessAdminService patientAccessAdminService;

    // ------------------------------------------------------------------ request listings

    @GetMapping("/pending-requests")
    public ResponseEntity<ApiResponse<List<PatientAccessRequestDto>>> getPendingRequests(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam String tinId) {

        log.info("Fetching pending requests for orgId: {}, providerId: {}, tinId: {}",
                organisationId, providerId, tinId);

        return data(patientAccessRequestService.getPendingRequests(organisationId, providerId, tinId));
    }

    @GetMapping("/access-granted")
    public ResponseEntity<ApiResponse<List<PatientAccessRequestDto>>> getAllGrantedRequests(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam String tinId) {

        return data(patientAccessRequestService.getGrantedRequests(organisationId, providerId, tinId));
    }

    @GetMapping("/access-revoked")
    public ResponseEntity<ApiResponse<List<PatientAccessRequestDto>>> getAllRevokedRequests(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam String tinId) {

        return data(patientAccessRequestService.getRevokedRequests(organisationId, providerId, tinId));
    }

    @GetMapping("/request/{requestId}")
    public ResponseEntity<ApiResponse<PatientAccessRequestDto>> getAccessRequest(
            @PathVariable Long requestId) {

        log.info("Fetching access request: {}", requestId);

        PatientAccessRequestDto request = patientAccessRequestService.getAccessRequestById(requestId);
        if (request == null) {
            throw new AppException(
                    ResponseCode.NOT_FOUND, "No access request found for id " + requestId + ".");
        }

        return data(request);
    }

    @GetMapping("/patient/{patientFhirId}")
    public ResponseEntity<ApiResponse<List<PatientAccessRequestDto>>> getPatientAccessRequests(
            @PathVariable String patientFhirId) {

        log.info("Fetching access requests for patient: {}", patientFhirId);

        return data(patientAccessRequestService.getPatientAccessRequests(patientFhirId));
    }

    // ------------------------------------------------------------------ decisions

    @PostMapping("/grant-access/{requestId}")
    public ResponseEntity<ApiResponse<PatientAccessRequestDto>> grantAccess(@PathVariable Long requestId) {
        log.info("Granting access for request: {} ", requestId);

        return data(ResponseCode.UPDATED, "Access granted successfully",
                patientAccessAdminService.grantAccess(requestId));
    }

    @PostMapping("/revoke-access/{requestId}")
    public ResponseEntity<ApiResponse<PatientAccessRequestDto>> revokeAccess(@PathVariable Long requestId) {
        log.info("Revoking access for request: {}", requestId);

        return data(ResponseCode.UPDATED, "Access revoked successfully",
                patientAccessAdminService.revokeAccess(requestId));
    }

    // ------------------------------------------------------------------ reporting data

    @GetMapping("/data/tin")
    public ResponseEntity<ApiResponse<PatientAccessDataDto>> getTinData(
            @RequestParam String tinId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching TIN data for tinId: {} from {} to {}", tinId, reportingPeriodStart, reportingPeriodEnd);

        ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
        return data(patientAccessDataService.getTinData(tinId, period.start(), period.end()));
    }

    @GetMapping("/data/clinic-provider")
    public ResponseEntity<ApiResponse<PatientAccessDataDto>> getTinProviderData(
            @RequestParam String tinId,
            @RequestParam String providerId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching TIN-Provider data for tinId: {}, providerId: {} from {} to {}",
                tinId, providerId, reportingPeriodStart, reportingPeriodEnd);

        ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
        return data(patientAccessDataService.getTinProviderData(
                tinId, providerId, period.start(), period.end()));
    }

    @GetMapping("/data/all")
    public ResponseEntity<ApiResponse<List<PatientAccessDataDto>>> getAllPatientMetrics(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching all patient metrics from {} to {}", reportingPeriodStart, reportingPeriodEnd);

        ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
        return data(patientAccessDataService.getAllPatientData(period.start(), period.end()));
    }

    // ------------------------------------------------------------------ dashboards

    /** Without a tinId the provider's whole caseload counts. */
    @GetMapping("/dashboard/patients-with-access")
    public ResponseEntity<ApiResponse<AccessDashboardResponse>> getDashboardWithPatientsWithAccess(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam(required = false) String tinId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching dashboard with patients who have access for orgId: {}, providerId: {}, tinId: {}",
                organisationId, providerId, tinId);

        ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
        return data(patientAccessAdminService.getProviderDashboard(
                organisationId, providerId, tinId, period));
    }

    @GetMapping("/dashboard/group-patients-with-access")
    public ResponseEntity<ApiResponse<AccessDashboardResponse>> getGroupDashboardWithPatientsWithAccess(
            @RequestParam String tinId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching group dashboard with patients who have access for group: {}", tinId);

        ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
        return data(patientAccessAdminService.getGroupDashboard(tinId, period));
    }
}
