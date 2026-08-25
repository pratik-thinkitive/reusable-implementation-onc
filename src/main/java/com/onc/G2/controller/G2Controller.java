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

@RequiredArgsConstructor
@RestController
@RequestMapping("/ehr/g2")
@Slf4j
public class G2Controller {

    private static final String ACCESS_DENIED_MESSAGE = "You do not currently have access to view your health information. Please request access for it.";

    private final EHRDataService ehrDataService;
    private final PatientAccessWorkflowService patientAccessWorkflowService;

    // Returns the patient's own personal details, if they have been granted access.
    @GetMapping("/personal-details")
    public ResponseEntity<?> fetchPatientMedicalDetails(@RequestParam String fhirId) {
        log.info("Medical details access request for patient: {}", fhirId);
        if (!patientAccessWorkflowService.checkAccessAndRecordView(fhirId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(accessDenied());
        }
        ResponseEntity<PersonalDetailsData> response = ehrDataService.fetchPatientPersonalDetails(fhirId);
        return response;
    }

    // Answers 409 when an existing request already blocks this one.
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

        AccessRequestResult result = patientAccessWorkflowService.requestAccess(fhirId, requestType, encounterId, providerId, tinId, reportingPeriodStart, reportingPeriodEnd);

        if (result.isDuplicate()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(accessRequestResponse(false, result.getMessage(), "DUPLICATE", result.getRequestId()));
        }

        return ResponseEntity.ok(accessRequestResponse(
                true, "Access request created successfully", "PENDING", result.getRequestId()));
    }

    private AccessDeniedResponse accessDenied() {
        AccessDeniedResponse response = new AccessDeniedResponse();
        response.setSuccess(false);
        response.setMessage(ACCESS_DENIED_MESSAGE);
        response.setAccessGranted(false);
        response.setRequestType(RequestType.MEDICAL_DETAILS_ACCESS.name());
        return response;
    }

    private AccessRequestResponse accessRequestResponse(boolean success, String message, String status, String requestId) {
        AccessRequestResponse response = new AccessRequestResponse();
        response.setSuccess(success);
        response.setMessage(message);
        response.setStatus(status);
        response.setRequestId(requestId);
        return response;
    }
}
