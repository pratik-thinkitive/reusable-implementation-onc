package com.onc.G2.controller;

import com.onc.EHR.dto.*;
import com.onc.EHR.service.EHRDataService;
import com.onc.G2.dto.AccessRequestResponse;
import com.onc.G2.dto.PatientAccessRequestDto;
import com.onc.G2.entity.PatientAccessRequest;
import com.onc.G2.service.PatientAccessRequestService;
import com.onc.G2.service.PatientAccessDataService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@RestController
@RequestMapping("/ehr/g2")
@Slf4j
public class G2Controller {

    private final EHRDataService ehrDataService;
    private final PatientAccessRequestService patientAccessRequestService;
    private final PatientAccessDataService patientAccessDataService;

    @GetMapping("/personal-details")
    public ResponseEntity<?> fetchPatientMedicalDetails(@RequestParam String fhirId) {
        log.info("Medical details access request for patient: {}", fhirId);
        
        try {
            boolean hasActiveAccess = patientAccessRequestService.hasActiveAccess(fhirId, PatientAccessRequest.RequestType.MEDICAL_DETAILS_ACCESS);
            
            if (hasActiveAccess) {
                log.info("Patient: {} has active access, fetching medical details", fhirId);
                
                // Update data - patient has access (numerator)
                LocalDate reportingPeriodStart = LocalDate.now().withDayOfYear(1);
                LocalDate reportingPeriodEnd = LocalDate.now().withMonth(12).withDayOfMonth(31);
                
                patientAccessDataService.updateNumerator(fhirId, reportingPeriodStart, reportingPeriodEnd, true, Instant.now());
                
                // Fetch and return personal details
                ResponseEntity<PersonalDetailsData> response = ehrDataService.fetchPatientPersonalDetails(fhirId);
                return response;
            } else {
                log.info("Patient: {} does not have active access. Returning access message.", fhirId);

                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "You do not currently have access to view your health information. Please request access for it.");
                response.put("accessGranted", false);
                response.put("requestType", "MEDICAL_DETAILS_ACCESS");

                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }
        } catch (Exception e) {
            log.error("Error processing medical details access request for patient: {}", fhirId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing request");
        }
    }

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
            PatientAccessRequest.RequestType type = PatientAccessRequest.RequestType.valueOf(requestType.toUpperCase());
            
            // Use provided dates or default to current year if not provided
            LocalDate startDate;
            LocalDate endDate;
            
            if (reportingPeriodStart != null && !reportingPeriodStart.isEmpty()) {
                startDate = LocalDate.parse(reportingPeriodStart);
            } else {
                startDate = LocalDate.now().withDayOfYear(1);
            }
            
            if (reportingPeriodEnd != null && !reportingPeriodEnd.isEmpty()) {
                endDate = LocalDate.parse(reportingPeriodEnd);
            } else {
                endDate = LocalDate.now().withMonth(12).withDayOfMonth(31);
            }
            
            // Extract patient details from EHRDataService to get organisation_id and tin_id
            PatientDetails patientDetails = extractPatientDetailsFromEHR(fhirId);
            
            // Create access request with extracted details
            // Note: The duplicate check is now handled inside createAccessRequest service method
            PatientAccessRequestDto requestDto = patientAccessRequestService.createAccessRequest(
                    fhirId, extractPatientId(fhirId), patientDetails.getFirstName(), patientDetails.getLastName(), patientDetails.getOrganisationId(),
                    providerId, tinId, type, encounterId,
                    null, // isFirstEncounter will be determined when encounter date is set
                    startDate, endDate);
            
            // Check if this is a duplicate request
            if (requestDto.getDuplicateRequest() != null && requestDto.getDuplicateRequest()) {
                AccessRequestResponse response = new AccessRequestResponse();
                response.setSuccess(false);
                response.setMessage(requestDto.getDuplicateMessage());
                response.setStatus("DUPLICATE");
                response.setRequestId(requestDto.getId().toString());
                
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
            
            // Initialize PatientAccessData entry immediately after creating the request
            // This ensures there is ONE entry per patient per reporting period
            patientAccessDataService.initializePatientData(
                    fhirId, extractPatientId(fhirId), patientDetails.getFirstName(), patientDetails.getLastName(),
                    patientDetails.getOrganisationId(), providerId, tinId, startDate, endDate);
            
            // Update denominator - patient had an encounter (when they created the request)
            patientAccessDataService.updateDenominator(fhirId, startDate, endDate, Instant.now()); // Current time is the encounter date
            
            AccessRequestResponse response = new AccessRequestResponse();
            response.setSuccess(true);
            response.setMessage("Access request created successfully");
            response.setStatus("PENDING");
            response.setRequestId(requestDto.getId().toString());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error creating access request for patient: {}", fhirId, e);
            AccessRequestResponse response = new AccessRequestResponse();
            response.setSuccess(false);
            response.setMessage("Error creating access request: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private String extractPatientId(String fhirId) {
        if (fhirId != null && fhirId.contains("-")) {
            return fhirId.split("-")[1];
        }
        return fhirId;
    }

     // Extract patient details from EHRDataService service to get organisation_id, provider_id, and tin_id

    private PatientDetails extractPatientDetailsFromEHR(String fhirId) {
        log.info("Extracting patient details from EHRDataService for fhirId: {}", fhirId);

        try {
            ResponseEntity<PersonalDetailsData> personalDetailsResponse = ehrDataService.fetchPatientPersonalDetails(fhirId);

            if (personalDetailsResponse != null && personalDetailsResponse.getStatusCode().is2xxSuccessful() && personalDetailsResponse.getBody() != null) {
                PersonalDetailsData personalDetails = personalDetailsResponse.getBody();
                PatientDetails patientDetails = new PatientDetails();
                patientDetails.setOrganisationId(personalDetails.getOrganisationId());
                patientDetails.setProviderId(String.valueOf(personalDetails.getCreatedBy()));

                if (personalDetails.getResponse() != null && personalDetails.getResponse().getPatientInformation() != null && !personalDetails.getResponse().getPatientInformation().isEmpty()) {
                    PatientInformation info = personalDetails.getResponse().getPatientInformation().values().stream().findFirst().orElse(null);

                    patientDetails.setFirstName(info.getFirstName());
                    patientDetails.setLastName(info.getLastName());

                    log.info("Extracted Patient Name: {} {}", info.getFirstName(), info.getLastName());
                } else {
                    log.warn("No PatientInformation found in response for fhirId: {}", fhirId);
                }

                List<Integer> clinicIds = fetchClinicIdsByDoctorId(personalDetails.getCreatedBy());
                String tinId = null;

                if (clinicIds != null && !clinicIds.isEmpty()) {
                    // Iterate through clinics and get the first valid TIN
                    for (Integer clinicId : clinicIds) {
                        tinId = extractTinIdFromClinicDetails(clinicId);
                        if (tinId != null && !tinId.isEmpty()) {
                            log.info("Found valid TIN ID: {} for clinic ID: {}", tinId, clinicId);
                            break;
                        }
                    }
                }

                if (tinId == null) {
                    log.warn("No valid TIN ID found for doctor {}", personalDetails.getCreatedBy());
                }

                patientDetails.setTinId(tinId);

                log.info("Successfully extracted patient details - FirstName: {}, LastName: {}, OrganisationId: {}, ProviderId: {}, TinId: {}",
                        patientDetails.getFirstName(), patientDetails.getLastName(),
                        patientDetails.getOrganisationId(), patientDetails.getProviderId(),
                        patientDetails.getTinId());

                return patientDetails;
            } else {
                log.warn("Failed to fetch personal details from EHRDataService for fhirId: {}", fhirId);
                return new PatientDetails();
            }

        } catch (Exception e) {
            log.error("Error extracting patient details from EHRDataService for fhirId: {}", fhirId, e);
            return new PatientDetails();
        }
    }

    private List<Integer> fetchClinicIdsByDoctorId(int doctorId) {
        ResponseEntity<DoctorDetailsData> doctorResponse = ehrDataService.fetchDoctorDetails(doctorId);
        if (doctorResponse != null && doctorResponse.getStatusCode().is2xxSuccessful() && doctorResponse.getBody() != null) {
            List<Clinic> clinics = doctorResponse.getBody().getClinics();
            if (clinics != null) {
                return clinics.stream().map(Clinic::getClinic_id).collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    //Get TIN ID for a clinic
    private String extractTinIdFromClinicDetails(int clinicId) {
        try {
            ResponseEntity<com.onc.EHR.dto.Clinic> clinicResponse = ehrDataService.fetchClinicDetails(clinicId);

            if (clinicResponse != null && clinicResponse.getStatusCode().is2xxSuccessful() &&
                    clinicResponse.getBody() != null) {

                com.onc.EHR.dto.Clinic clinicDetails = clinicResponse.getBody();

                if (clinicDetails.getTax_identification_number() != null && !clinicDetails.getTax_identification_number().isEmpty()) {
                    log.info("Successfully extracted TIN ID: {} for clinic: {}", clinicDetails.getTax_identification_number(), clinicId);
                    return clinicDetails.getTax_identification_number();
                }
            }

            log.warn("Could not extract TIN ID for clinic: {}", clinicId);
            return null;

        } catch (Exception e) {
            log.error("Error extracting TIN ID for clinic: {}", clinicId, e);
            return null;
        }
    }

    // Inner class to hold patient details extracted from EHRDataService
    @Getter
    @Setter
    private static class PatientDetails {
        private String firstName;
        private String lastName;
        private Integer organisationId;
        private String providerId;
        private String tinId;
    }
}
