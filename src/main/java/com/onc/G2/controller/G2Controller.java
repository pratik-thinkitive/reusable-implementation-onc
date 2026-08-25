package com.onc.G2.controller;

import com.onc.EHR.dto.PersonalDetailsData;
import com.onc.EHR.service.EHRDataService;
import com.onc.G2.dto.AccessRequestResult;
import com.onc.G2.enums.RequestType;
import com.onc.G2.model.ReportingPeriod;
import com.onc.G2.service.PatientAccessWorkflowService;
import com.onc.api.support.ApiResponse;
import com.onc.api.support.BaseController;
import com.onc.api.support.ResponseCode;
import com.onc.common.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RequiredArgsConstructor
@RestController
@RequestMapping("/ehr/g2")
@Slf4j
public class G2Controller extends BaseController {

    private static final String ACCESS_DENIED_MESSAGE =
            "You do not currently have access to view your health information. "
                    + "Please request access for it.";

    private final EHRDataService ehrDataService;
    private final PatientAccessWorkflowService patientAccessWorkflowService;

    /**
     * Returns the patient's own personal details, if they have been granted access.
     *
     * <p>Note the access check keys on the supplied {@code fhirId} rather than on an
     * authenticated identity - there is no authentication in front of this endpoint yet.
     */
    @GetMapping("/personal-details")
    public ResponseEntity<ApiResponse<PersonalDetailsData>> fetchPatientMedicalDetails(
            @RequestParam String fhirId) {

        log.info("Medical details access request for patient: {}", fhirId);

        if (!patientAccessWorkflowService.checkAccessAndRecordView(fhirId)) {
            throw new AppException(ResponseCode.PATIENT_ACCESS_DENIED, ACCESS_DENIED_MESSAGE);
        }

        return data(ehrDataService.fetchPatientPersonalDetails(fhirId));
    }

    /**
     * Files an access request. Answers 409 when an existing request already blocks this one,
     * carrying the blocking request's id so the caller can look it up.
     */
    @PostMapping("/request-access")
    public ResponseEntity<ApiResponse<AccessRequestResult>> requestAccess(
            @RequestParam String fhirId,
            @RequestParam RequestType requestType,
            @RequestParam String encounterId,
            @RequestParam String providerId,
            @RequestParam String tinId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate reportingPeriodStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate reportingPeriodEnd) {

        log.info("Access request for patient: {} with type: {}", fhirId, requestType);

        ReportingPeriod period = ReportingPeriod.of(reportingPeriodStart, reportingPeriodEnd);

        AccessRequestResult result = patientAccessWorkflowService.requestAccess(
                fhirId, requestType, encounterId, providerId, tinId, period);

        if (result.isDuplicate()) {
            return data(ResponseCode.DUPLICATE_REQUEST, result.getMessage(), result);
        }

        return data(ResponseCode.CREATED, "Access request created successfully", result);
    }
}
