package com.onc.G2.controller;

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

/**
 * Administrator endpoints for reviewing access requests and reading measure performance.
 *
 * <p>Only describes the successful path. Failures are turned into responses by
 * {@link com.onc.G2.exception.G2ExceptionHandler}.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/ehr/admin/patient-access")
@Slf4j
public class PatientAccessAdminController {

    private final PatientAccessRequestService patientAccessRequestService;
    private final PatientAccessDataService patientAccessDataService;
    private final PatientAccessAdminService patientAccessAdminService;

    // ------------------------------------------------------------------ request listings

    /** Requests still awaiting a decision. */
    @GetMapping("/pending-requests")
    public ResponseEntity<List<PatientAccessRequestDto>> getPendingRequests(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam String tinId) {

        log.info("Fetching pending requests for orgId: {}, providerId: {}, tinId: {}",
                organisationId, providerId, tinId);

        return ResponseEntity.ok(
                patientAccessRequestService.getPendingRequests(organisationId, providerId, tinId));
    }

    /** Requests that were approved. */
    @GetMapping("/access-granted")
    public ResponseEntity<List<PatientAccessRequestDto>> getAllGrantedRequests(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam String tinId) {

        return ResponseEntity.ok(
                patientAccessRequestService.getGrantedRequests(organisationId, providerId, tinId));
    }

    /** Requests whose access was later withdrawn. */
    @GetMapping("/access-revoked")
    public ResponseEntity<List<PatientAccessRequestDto>> getAllRevokedRequests(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam String tinId) {

        return ResponseEntity.ok(
                patientAccessRequestService.getRevokedRequests(organisationId, providerId, tinId));
    }

    /** A single request by its id. */
    @GetMapping("/request/{requestId}")
    public ResponseEntity<PatientAccessRequestDto> getAccessRequest(@PathVariable Long requestId) {
        log.info("Fetching access request: {}", requestId);

        PatientAccessRequestDto request = patientAccessRequestService.getAccessRequestById(requestId);
        return request != null ? ResponseEntity.ok(request) : ResponseEntity.notFound().build();
    }

    /** Every request belonging to one patient. */
    @GetMapping("/patient/{patientFhirId}")
    public ResponseEntity<List<PatientAccessRequestDto>> getPatientAccessRequests(
            @PathVariable String patientFhirId) {

        log.info("Fetching access requests for patient: {}", patientFhirId);

        return ResponseEntity.ok(patientAccessRequestService.getPatientAccessRequests(patientFhirId));
    }

    // ------------------------------------------------------------------ decisions

    /** Approves a pending request. */
    @PostMapping("/grant-access/{requestId}")
    public ResponseEntity<AccessRequestResponse> grantAccess(@PathVariable Long requestId) {
        log.info("Granting access for request: {} ", requestId);

        return toResponse(patientAccessAdminService.grantAccess(requestId));
    }

    /** Withdraws access that was previously granted. */
    @PostMapping("/revoke-access/{requestId}")
    public ResponseEntity<AccessRequestResponse> revokeAccess(@PathVariable Long requestId) {
        log.info("Revoking access for request: {}", requestId);

        return toResponse(patientAccessAdminService.revokeAccess(requestId));
    }

    // ------------------------------------------------------------------ reporting data

    /** Measure totals for one TIN. */
    @GetMapping("/data/tin")
    public ResponseEntity<PatientAccessDataDto> getTinData(
            @RequestParam String tinId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching TIN data for tinId: {} from {} to {}", tinId, reportingPeriodStart, reportingPeriodEnd);

        ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
        return ResponseEntity.ok(patientAccessDataService.getTinData(tinId, period.start(), period.end()));
    }

    /** Measure totals for one provider within one TIN. */
    @GetMapping("/data/clinic-provider")
    public ResponseEntity<PatientAccessDataDto> getTinProviderData(
            @RequestParam String tinId,
            @RequestParam String providerId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching TIN-Provider data for tinId: {}, providerId: {} from {} to {}",
                tinId, providerId, reportingPeriodStart, reportingPeriodEnd);

        ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
        return ResponseEntity.ok(
                patientAccessDataService.getTinProviderData(tinId, providerId, period.start(), period.end()));
    }

    /** Every patient row in a reporting period. */
    @GetMapping("/data/all")
    public ResponseEntity<List<PatientAccessDataDto>> getAllPatientMetrics(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching all patient metrics from {} to {}", reportingPeriodStart, reportingPeriodEnd);

        ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
        return ResponseEntity.ok(patientAccessDataService.getAllPatientData(period.start(), period.end()));
    }

    // ------------------------------------------------------------------ dashboards

    /** Performance for one provider, optionally narrowed to a single TIN. */
    @GetMapping("/dashboard/patients-with-access")
    public ResponseEntity<AccessDashboardResponse> getDashboardWithPatientsWithAccess(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam(required = false) String tinId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching dashboard with patients who have access for orgId: {}, providerId: {}, tinId: {}",
                organisationId, providerId, tinId);

        ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
        return ResponseEntity.ok(
                patientAccessAdminService.getProviderDashboard(organisationId, providerId, tinId, period));
    }

    /** Performance across every provider billing under one TIN. */
    @GetMapping("/dashboard/group-patients-with-access")
    public ResponseEntity<AccessDashboardResponse> getGroupDashboardWithPatientsWithAccess(
            @RequestParam String tinId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching group dashboard with patients who have access for group: {}", tinId);

        ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
        return ResponseEntity.ok(patientAccessAdminService.getGroupDashboard(tinId, period));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A refused decision is a client problem, so it answers 400; an accepted one answers 200.
     * Unexpected failures never reach here - they go to the exception handler instead.
     */
    private ResponseEntity<AccessRequestResponse> toResponse(AccessRequestResponse response) {
        return response.isSuccess()
                ? ResponseEntity.ok(response)
                : ResponseEntity.badRequest().body(response);
    }
}
