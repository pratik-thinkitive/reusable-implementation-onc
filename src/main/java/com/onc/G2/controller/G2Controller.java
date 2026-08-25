package com.onc.G2.controller;

import com.onc.EHR.dto.PersonalDetailsData;
import com.onc.EHR.service.EHRDataService;
import com.onc.G2.dto.AccessRequestResponse;
import com.onc.G2.dto.AccessRequestResult;
import com.onc.G2.service.PatientAccessWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Patient-facing G2 endpoints.
 *
 * <p>This class does only what a controller should: read the request, hand off to
 * {@link PatientAccessWorkflowService}, and turn the answer into a status code and a body. All
 * the decision-making lives in the service.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/ehr/g2")
@Slf4j
public class G2Controller {

    private final EHRDataService ehrDataService;
    private final PatientAccessWorkflowService patientAccessWorkflowService;

    /**
     * Returns the patient's own personal details, but only if they have been granted access.
     */
    @GetMapping("/personal-details")
    public ResponseEntity<?> fetchPatientMedicalDetails(@RequestParam String fhirId) {
        log.info("Medical details access request for patient: {}", fhirId);

        try {
            if (!patientAccessWorkflowService.checkAccessAndRecordView(fhirId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(accessDeniedBody());
            }

            // Passed straight through so the upstream status code survives untouched.
            ResponseEntity<PersonalDetailsData> response = ehrDataService.fetchPatientPersonalDetails(fhirId);
            return response;

        } catch (Exception e) {
            log.error("Error processing medical details access request for patient: {}", fhirId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing request");
        }
    }

    /**
     * Records a patient's request for access to their health information.
     *
     * <p>Answers {@code 409 Conflict} when an existing request already blocks this one.
     */
    @PostMapping("/request-access")
    public ResponseEntity<AccessRequestResponse> requestAccess(
            @RequestParam String fhirId,
            @RequestParam String requestType,
            @RequestParam String encounterId,
            @RequestParam String providerId,
            @RequestParam String tinId,
            @RequestParam(required = false) String reportingPeriodStart,
            @RequestParam(required = false) String reportingPeriodEnd) {

        log.info("Access request for patient: {} with type: {}", fhirId, requestType);

        try {
            AccessRequestResult result = patientAccessWorkflowService.requestAccess(
                    fhirId, requestType, encounterId, providerId, tinId,
                    reportingPeriodStart, reportingPeriodEnd);

            if (result.isDuplicate()) {
                AccessRequestResponse response = new AccessRequestResponse();
                response.setSuccess(false);
                response.setMessage(result.getMessage());
                response.setStatus("DUPLICATE");
                response.setRequestId(result.getRequestId());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }

            AccessRequestResponse response = new AccessRequestResponse();
            response.setSuccess(true);
            response.setMessage("Access request created successfully");
            response.setStatus("PENDING");
            response.setRequestId(result.getRequestId());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error creating access request for patient: {}", fhirId, e);
            AccessRequestResponse response = new AccessRequestResponse();
            response.setSuccess(false);
            response.setMessage("Error creating access request: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /** The advisory shown to a patient who has not been granted access yet. */
    private Map<String, Object> accessDeniedBody() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "You do not currently have access to view your health information. "
                + "Please request access for it.");
        response.put("accessGranted", false);
        response.put("requestType", "MEDICAL_DETAILS_ACCESS");
        return response;
    }
}
