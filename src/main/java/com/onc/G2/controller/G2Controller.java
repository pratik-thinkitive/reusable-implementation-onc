package com.onc.G2.controller;

import com.onc.EHR.dto.PersonalDetailsData;
import com.onc.EHR.service.EHRDataService;
import com.onc.G2.dto.AccessDeniedResponse;
import com.onc.G2.dto.AccessRequestResponse;
import com.onc.G2.dto.AccessRequestResult;
import com.onc.G2.enums.RequestType;
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

/**
 * Patient-facing G2 endpoints.
 *
 * <p>Only describes the successful path. Anything that fails is turned into a response by
 * {@link com.onc.G2.exception.G2ExceptionHandler}, which is why there is no error handling here.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/ehr/g2")
@Slf4j
public class G2Controller {

    private static final String ACCESS_DENIED_MESSAGE =
            "You do not currently have access to view your health information. Please request access for it.";

    private final EHRDataService ehrDataService;
    private final PatientAccessWorkflowService patientAccessWorkflowService;

    /**
     * Returns the patient's own personal details, but only if they have been granted access.
     */
    @GetMapping("/personal-details")
    public ResponseEntity<?> fetchPatientMedicalDetails(@RequestParam String fhirId) {
        log.info("Medical details access request for patient: {}", fhirId);

        if (!patientAccessWorkflowService.checkAccessAndRecordView(fhirId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(accessDenied());
        }

        // Passed straight through so the upstream status code survives untouched.
        ResponseEntity<PersonalDetailsData> response = ehrDataService.fetchPatientPersonalDetails(fhirId);
        return response;
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

        AccessRequestResult result = patientAccessWorkflowService.requestAccess(
                fhirId, requestType, encounterId, providerId, tinId,
                reportingPeriodStart, reportingPeriodEnd);

        if (result.isDuplicate()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    accessRequestResponse(false, result.getMessage(), "DUPLICATE", result.getRequestId()));
        }

        return ResponseEntity.ok(accessRequestResponse(
                true, "Access request created successfully", "PENDING", result.getRequestId()));
    }

    /** The advisory shown to a patient who has not been granted access yet. */
    private AccessDeniedResponse accessDenied() {
        AccessDeniedResponse response = new AccessDeniedResponse();
        response.setSuccess(false);
        response.setMessage(ACCESS_DENIED_MESSAGE);
        response.setAccessGranted(false);
        response.setRequestType(RequestType.MEDICAL_DETAILS_ACCESS.name());
        return response;
    }

    private AccessRequestResponse accessRequestResponse(boolean success, String message,
                                                        String status, String requestId) {
        AccessRequestResponse response = new AccessRequestResponse();
        response.setSuccess(success);
        response.setMessage(message);
        response.setStatus(status);
        response.setRequestId(requestId);
        return response;
    }
}
