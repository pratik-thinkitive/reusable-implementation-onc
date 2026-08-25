package com.onc.G2.controller;

import com.onc.G2.dto.AccessRequestResponse;
import com.onc.G2.dto.PatientAccessRequestDto;
import com.onc.G2.dto.PatientAccessDataDto;
import com.onc.G2.service.PatientAccessRequestService;
import com.onc.G2.service.PatientAccessDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/ehr/admin/patient-access")
@Slf4j
public class PatientAccessAdminController {

    private final PatientAccessRequestService patientAccessRequestService;
    private final PatientAccessDataService patientAccessDataService;

     //Get all pending access requests for admin review
    @GetMapping("/pending-requests")
    public ResponseEntity<List<PatientAccessRequestDto>> getPendingRequests(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam String tinId) {
        
        log.info("Fetching pending requests for orgId: {}, providerId: {}, tinId: {}", organisationId, providerId, tinId);
        
        try {
            List<PatientAccessRequestDto> requests = patientAccessRequestService.getPendingRequests(organisationId, providerId, tinId);
            return ResponseEntity.ok(requests);
        } catch (Exception e) {
            log.error("Error fetching pending requests", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/access-granted")
    public ResponseEntity<List<PatientAccessRequestDto>> getAllGrantedRequests(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam String tinId) {
        try {
            List<PatientAccessRequestDto> grantedRequests = patientAccessRequestService.getGrantedRequests(organisationId, providerId, tinId);
            return ResponseEntity.ok(grantedRequests);
        } catch (Exception e){
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

    }

    @GetMapping("/access-revoked")
    public ResponseEntity<List<PatientAccessRequestDto>> getAllRevokedRequests(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam String tinId) {
        try {
            List<PatientAccessRequestDto> revokedRequests = patientAccessRequestService.getRevokedRequests(organisationId, providerId, tinId);
            return ResponseEntity.ok(revokedRequests);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    //Grant access to patient (directly from pending)
    @PostMapping("/grant-access/{requestId}")
    public ResponseEntity<AccessRequestResponse> grantAccess(@PathVariable Long requestId) {
        
        log.info("Granting access for request: {} ", requestId);
        
        try {
            AccessRequestResponse response = patientAccessRequestService.grantAccess(requestId);
            
            if (response.isSuccess()) {
                // Update metrics - access granted
                PatientAccessRequestDto request = patientAccessRequestService.getAccessRequestById(requestId);
                if (request != null) {
                    LocalDate reportingPeriodStart = request.getReportingPeriodStart();
                    LocalDate reportingPeriodEnd = request.getReportingPeriodEnd();

                    // PatientAccessData should already exist (created when request was made)
                    // But call initializePatientData anyway to ensure it exists (it will return existing entry if found)
                    patientAccessDataService.initializePatientData(
                            request.getPatientFhirId(),
                            request.getPatientId(),
                            request.getFirstName(),
                            request.getLastName(),
                            request.getOrganisationId(),
                            request.getProviderId(),
                            request.getTinId(),
                            reportingPeriodStart,
                            reportingPeriodEnd
                    );

                    // Update denominator - access being granted implies patient had an encounter
                    // Use requestedAt as the encounter date (when patient requested access during encounter)
                    // If requestedAt is not available, use accessGrantedAt as fallback
                    Instant encounterDate = request.getRequestedAt() != null 
                            ? request.getRequestedAt() 
                            : request.getAccessGrantedAt() != null 
                                ? request.getAccessGrantedAt() 
                                : Instant.now();
                    
                    patientAccessDataService.updateDenominator(
                            request.getPatientFhirId(),
                            reportingPeriodStart,
                            reportingPeriodEnd,
                            encounterDate );
                    
                    // Update numerator when access is granted
                    // Use accessGrantedAt timestamp from the request (not Instant.now())
                    Instant accessGrantedDate = request.getAccessGrantedAt() != null 
                            ? request.getAccessGrantedAt() 
                            : Instant.now();
                    
                    patientAccessDataService.updateNumerator(
                            request.getPatientFhirId(), 
                            reportingPeriodStart, 
                            reportingPeriodEnd, 
                            true,  // hasAccess is set as true because access was granted
                            accessGrantedDate );
                }
                
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body(response);
            }
        } catch (Exception e) {
            log.error("Error granting access for request: {}", requestId, e);
            AccessRequestResponse response = new AccessRequestResponse();
            response.setSuccess(false);
            response.setMessage("Error granting access: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

     //Revoke access from patient
    @PostMapping("/revoke-access/{requestId}")
    public ResponseEntity<AccessRequestResponse> revokeAccess(
            @PathVariable Long requestId) {
        
        log.info("Revoking access for request: {}", requestId);
        
        try {
            AccessRequestResponse response = patientAccessRequestService.revokeAccess(requestId);
            
            if (response.isSuccess()) {
                // Update metrics - access revoked (decrement numerator)
                PatientAccessRequestDto request = patientAccessRequestService.getAccessRequestById(requestId);
                if (request != null) {
                    LocalDate reportingPeriodStart = request.getReportingPeriodStart();
                    LocalDate reportingPeriodEnd = request.getReportingPeriodEnd();
                    
                    patientAccessDataService.decrementNumerator(
                            request.getPatientFhirId(), 
                            reportingPeriodStart, 
                            reportingPeriodEnd );
                }
                
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body(response);
            }
        } catch (Exception e) {
            log.error("Error revoking access for request: {}", requestId, e);
            AccessRequestResponse response = new AccessRequestResponse();
            response.setSuccess(false);
            response.setMessage("Error revoking access: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    //Get access request details by ID
    @GetMapping("/request/{requestId}")
    public ResponseEntity<PatientAccessRequestDto> getAccessRequest(@PathVariable Long requestId) {
        log.info("Fetching access request: {}", requestId);
        
        try {
            PatientAccessRequestDto request = patientAccessRequestService.getAccessRequestById(requestId);
            if (request != null) {
                return ResponseEntity.ok(request);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("Error fetching access request: {}", requestId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

     //Get access requests for a specific patient
    @GetMapping("/patient/{patientFhirId}")
    public ResponseEntity<List<PatientAccessRequestDto>> getPatientAccessRequests(
            @PathVariable String patientFhirId) {
        
        log.info("Fetching access requests for patient: {}", patientFhirId);
        
        try {
            List<PatientAccessRequestDto> requests = patientAccessRequestService.getPatientAccessRequests(patientFhirId);
            return ResponseEntity.ok(requests);
        } catch (Exception e) {
            log.error("Error fetching access requests for patient: {}", patientFhirId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    //Get MIPS reporting data for TIN
    @GetMapping("/data/tin")
    public ResponseEntity<PatientAccessDataDto> getTinData(
            @RequestParam String tinId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching TIN data for tinId: {} from {} to {}", tinId, reportingPeriodStart, reportingPeriodEnd);

        try {
            LocalDate startDate = reportingPeriodStart != null
                    ? reportingPeriodStart
                    : LocalDate.now().withDayOfYear(1);

            LocalDate endDate = reportingPeriodEnd != null
                    ? reportingPeriodEnd
                    : LocalDate.now().withMonth(12).withDayOfMonth(31);

            PatientAccessDataDto data = patientAccessDataService.getTinData( tinId, startDate, endDate);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error fetching TIN data for tinId: {}", tinId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/data/clinic-provider")
    public ResponseEntity<PatientAccessDataDto> getTinProviderData(
            @RequestParam String tinId,
            @RequestParam String providerId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching TIN-Provider data for tinId: {}, providerId: {} from {} to {}",
                tinId, providerId, reportingPeriodStart, reportingPeriodEnd);

        try {
            LocalDate startDate = reportingPeriodStart != null
                    ? reportingPeriodStart
                    : LocalDate.now().withDayOfYear(1);

            LocalDate endDate = reportingPeriodEnd != null
                    ? reportingPeriodEnd
                    : LocalDate.now().withMonth(12).withDayOfMonth(31);

            // Call service
            PatientAccessDataDto data = patientAccessDataService.getTinProviderData(tinId, providerId, startDate, endDate);

            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error fetching TIN-Provider metrics for tinId: {}, providerId: {}", tinId, providerId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    //Get all patient metrics for a reporting period
    @GetMapping("/data/all")
    public ResponseEntity<List<PatientAccessDataDto>> getAllPatientMetrics(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {
        
        log.info("Fetching all patient metrics from {} to {}", reportingPeriodStart, reportingPeriodEnd);
        
        try {
            LocalDate startDate = reportingPeriodStart != null
                    ? reportingPeriodStart
                    : LocalDate.now().withDayOfYear(1);

            LocalDate endDate = reportingPeriodEnd != null
                    ? reportingPeriodEnd
                    : LocalDate.now().withMonth(12).withDayOfMonth(31);

            List<PatientAccessDataDto> metrics = patientAccessDataService.getAllPatientData(startDate, endDate);
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            log.error("Error fetching all patient metrics", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/dashboard/patients-with-access")
    public ResponseEntity<Map<String, Object>> getDashboardWithPatientsWithAccess(
            @RequestParam Integer organisationId,
            @RequestParam String providerId,
            @RequestParam (required = false) String tinId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching dashboard with patients who have access for orgId: {}, providerId: {}, tinId: {}",
                organisationId, providerId, tinId);

        try {
            // Default to the current calendar year when no period is supplied
            LocalDate startDate = reportingPeriodStart != null
                    ? reportingPeriodStart
                    : LocalDate.now().withDayOfYear(1);

            LocalDate endDate = reportingPeriodEnd != null
                    ? reportingPeriodEnd
                    : LocalDate.now().withMonth(12).withDayOfMonth(31);

            // Get patients with access (filtered)
            List<PatientAccessDataDto> patientsWithAccess = patientAccessDataService
                    .getAccessGrantedPatientsFiltered(organisationId, providerId, tinId, startDate, endDate);

            int totalDenominator = patientsWithAccess.stream().mapToInt(PatientAccessDataDto::getDenominatorCount).sum();
            int totalNumerator = patientsWithAccess.stream().mapToInt(PatientAccessDataDto::getNumeratorCount).sum();
            double percentage = totalDenominator > 0 ? (double) totalNumerator / totalDenominator * 100.0 : 0.0;

            Map<String, Object> dashboard = new LinkedHashMap<>();
            dashboard.put("patientsWithAccess", patientsWithAccess);
            dashboard.put("reportingPeriodStart", startDate.toString());
            dashboard.put("reportingPeriodEnd", endDate.toString());
            dashboard.put("totalNumerator", totalNumerator);
            dashboard.put("totalDenominator", totalDenominator);
            dashboard.put("percentage", percentage);

            return ResponseEntity.ok(dashboard);
        } catch (Exception e) {
            log.error("Error fetching dashboard with patients who have access", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/dashboard/group-patients-with-access")
    public ResponseEntity<Map<String, Object>> getGroupDashboardWithPatientsWithAccess(
            @RequestParam String tinId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodStart,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate reportingPeriodEnd) {

        log.info("Fetching group dashboard with patients who have access for group: {}", tinId);

        try {

            LocalDate startDate = reportingPeriodStart != null
                    ? reportingPeriodStart
                    : LocalDate.now().withDayOfYear(1);

            LocalDate endDate = reportingPeriodEnd != null
                    ? reportingPeriodEnd
                    : LocalDate.now().withMonth(12).withDayOfMonth(31);

            List<PatientAccessDataDto> patientsWithAccess = patientAccessDataService.getAccessGrantedPatientsForGroup(tinId, startDate, endDate);

            int totalDenominator = patientsWithAccess.stream().mapToInt(PatientAccessDataDto::getDenominatorCount).sum();
            int totalNumerator = patientsWithAccess.stream().mapToInt(PatientAccessDataDto::getNumeratorCount).sum();
            double percentage = totalDenominator > 0 ? (double) totalNumerator / totalDenominator * 100.0 : 0.0;

            Map<String, Object> dashboard = new LinkedHashMap<>();
            dashboard.put("groupId", tinId);
            dashboard.put("patientsWithAccess", patientsWithAccess);
            dashboard.put("reportingPeriodStart", startDate.toString());
            dashboard.put("reportingPeriodEnd", endDate.toString());
            dashboard.put("totalNumerator", totalNumerator);
            dashboard.put("totalDenominator", totalDenominator);
            dashboard.put("percentage", percentage);

            return ResponseEntity.ok(dashboard);
        } catch (Exception e) {
            log.error("Error fetching group dashboard with patients who have access", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
