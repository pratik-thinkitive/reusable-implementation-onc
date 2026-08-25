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
import org.springframework.http.HttpStatus;
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
 * <p>Reads the request, calls a service, maps the answer to a status code. Anything that decides
 * <em>what should happen</em> lives in {@link PatientAccessAdminService}.
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

        try {
            return ResponseEntity.ok(
                    patientAccessRequestService.getPendingRequests(organisationId, providerId, tinId));
        } catch (Exception e) {
            log.error("Error fetching pending requests", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Requests that were approved. */
    @GetMapping("/access-granted")
    public ResponseEntity<List<PatientAccessRequestDto>> getAllGrantedRequests(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam String tinId) {

        try {
            return ResponseEntity.ok(
                    patientAccessRequestService.getGrantedRequests(organisationId, providerId, tinId));
        } catch (Exception e) {
            log.error("Error fetching granted requests", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Requests whose access was later withdrawn. */
    @GetMapping("/access-revoked")
    public ResponseEntity<List<PatientAccessRequestDto>> getAllRevokedRequests(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam String tinId) {

        try {
            return ResponseEntity.ok(
                    patientAccessRequestService.getRevokedRequests(organisationId, providerId, tinId));
        } catch (Exception e) {
            log.error("Error fetching revoked requests", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** A single request by its id. */
    @GetMapping("/request/{requestId}")
    public ResponseEntity<PatientAccessRequestDto> getAccessRequest(@PathVariable Long requestId) {
        log.info("Fetching access request: {}", requestId);

        try {
            PatientAccessRequestDto request = patientAccessRequestService.getAccessRequestById(requestId);
            return request != null ? ResponseEntity.ok(request) : ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error fetching access request: {}", requestId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Every request belonging to one patient. */
    @GetMapping("/patient/{patientFhirId}")
    public ResponseEntity<List<PatientAccessRequestDto>> getPatientAccessRequests(
            @PathVariable String patientFhirId) {

        log.info("Fetching access requests for patient: {}", patientFhirId);

        try {
            return ResponseEntity.ok(patientAccessRequestService.getPatientAccessRequests(patientFhirId));
        } catch (Exception e) {
            log.error("Error fetching access requests for patient: {}", patientFhirId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ------------------------------------------------------------------ decisions

    /** Approves a pending request. */
    @PostMapping("/grant-access/{requestId}")
    public ResponseEntity<AccessRequestResponse> grantAccess(@PathVariable Long requestId) {
        log.info("Granting access for request: {} ", requestId);

        try {
            AccessRequestResponse response = patientAccessAdminService.grantAccess(requestId);
            return response.isSuccess()
                    ? ResponseEntity.ok(response)
                    : ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("Error granting access for request: {}", requestId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse("Error granting access: " + e.getMessage()));
        }
    }

    /** Withdraws access that was previously granted. */
    @PostMapping("/revoke-access/{requestId}")
    public ResponseEntity<AccessRequestResponse> revokeAccess(@PathVariable Long requestId) {
        log.info("Revoking access for request: {}", requestId);

        try {
            AccessRequestResponse response = patientAccessAdminService.revokeAccess(requestId);
            return response.isSuccess()
                    ? ResponseEntity.ok(response)
                    : ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("Error revoking access for request: {}", requestId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse("Error revoking access: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ reporting data

    /** Measure totals for one TIN. */
    @GetMapping("/data/tin")
    public ResponseEntity<PatientAccessDataDto> getTinData(
            @RequestParam String tinId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching TIN data for tinId: {} from {} to {}", tinId, reportingPeriodStart, reportingPeriodEnd);

        try {
            ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
            return ResponseEntity.ok(
                    patientAccessDataService.getTinData(tinId, period.start(), period.end()));
        } catch (Exception e) {
            log.error("Error fetching TIN data for tinId: {}", tinId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
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

        try {
            ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
            return ResponseEntity.ok(
                    patientAccessDataService.getTinProviderData(tinId, providerId, period.start(), period.end()));
        } catch (Exception e) {
            log.error("Error fetching TIN-Provider metrics for tinId: {}, providerId: {}", tinId, providerId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Every patient row in a reporting period. */
    @GetMapping("/data/all")
    public ResponseEntity<List<PatientAccessDataDto>> getAllPatientMetrics(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching all patient metrics from {} to {}", reportingPeriodStart, reportingPeriodEnd);

        try {
            ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
            return ResponseEntity.ok(
                    patientAccessDataService.getAllPatientData(period.start(), period.end()));
        } catch (Exception e) {
            log.error("Error fetching all patient metrics", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
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

        try {
            ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
            return ResponseEntity.ok(patientAccessAdminService
                    .getProviderDashboard(organisationId, providerId, tinId, period));
        } catch (Exception e) {
            log.error("Error fetching dashboard with patients who have access", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Performance across every provider billing under one TIN. */
    @GetMapping("/dashboard/group-patients-with-access")
    public ResponseEntity<AccessDashboardResponse> getGroupDashboardWithPatientsWithAccess(
            @RequestParam String tinId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching group dashboard with patients who have access for group: {}", tinId);

        try {
            ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);
            return ResponseEntity.ok(patientAccessAdminService.getGroupDashboard(tinId, period));
        } catch (Exception e) {
            log.error("Error fetching group dashboard with patients who have access", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ------------------------------------------------------------------ helpers

    private AccessRequestResponse errorResponse(String message) {
        AccessRequestResponse response = new AccessRequestResponse();
        response.setSuccess(false);
        response.setMessage(message);
        return response;
    }
}
