package com.onc.C2.service;

import com.onc.C2.measure.MeasureEvaluator;
import com.onc.C2.dto.PatientMeasureData;
import com.onc.EHR.service.EHRTokenService;
import com.onc.EHR.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Service to fetch patient data from EHR and convert to PatientMeasureData
 * for measure calculation and QRDA-III generation
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatientSummaryService {

    @Value("${ehr.api.base-url}")
    private String apiBaseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final EHRTokenService ehrTokenService;

    /** The authenticated GET entity every read in this service shares. */
    private HttpEntity<Void> authorizedRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ehrTokenService.getAccessToken());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    /**
     * Fetch patient data from EHR and convert to PatientMeasureData
     */
    public PatientMeasureData fetchPatientData(String patientId) {
        try {
            PatientMeasureData patientMeasureData = new PatientMeasureData();
            patientMeasureData.setPatientId(patientId);

            // One token for this patient's reads. Each fetch used to mint its own, so a single
            // patient cost four password-grant round trips before any data was fetched.
            HttpEntity<Void> request = authorizedRequest();

            // Fetch personal details (matches QRDA package approach)
            PersonalDetailsData personalDetails = fetchPersonalDetails(patientId, request);
            patientMeasureData.setPersonalDetailsData(personalDetails);

            // Fetch insurance details (matches QRDA package approach)
            List<InsuranceDetails> insuranceDetails = fetchInsuranceDetails(patientId, request);
            patientMeasureData.setInsuranceDetails(insuranceDetails);

            // Fetch appointment data (matches QRDA package approach)
            String clinicId = "762";
            AppointmentData appointmentData = fetchAppointmentData(patientId, clinicId, request);
            patientMeasureData.setAppointmentData(appointmentData);

            // Fetch SOAP contexts and FormData (matches QRDA package approach - fetchSoapDetails)
            List<FormData> soapDetails = fetchSoapDetails(patientId, request);
            
            // Set clinic ID (already extracted above)
            patientMeasureData.setClinicId(clinicId);

            // Set default measure information (CMS139)
            patientMeasureData.setMeasureId("5cd5918e-b31d-4ecb-af3e-24923d0d594e");
            patientMeasureData.setMeasureName("Falls: Screening for Future Fall Risk");

            // Extract encounters from appointments
            MeasureEvaluator.extractEncounters(patientMeasureData);

            // Store FormResponse objects instead of extracting AssessmentData/InterventionData
            List<FormResponse> formResponses = extractFormResponses(soapDetails);
            patientMeasureData.setFormResponses(formResponses);
            
            log.info("Patient {} - Stored {} FormResponse(s) from FormData (QRDA package approach)",
                    patientId, formResponses.size());

            log.debug("Successfully fetched patient data for ID: {}", patientId);
            return patientMeasureData;

        } catch (Exception e) {
            log.error("Error fetching patient data for ID {}: {}", patientId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Fetch multiple patients
     */
    public List<PatientMeasureData> fetchPatients(List<String> patientIds) {
        log.info("Fetching data for {} patient IDs", patientIds.size());
        List<PatientMeasureData> patients = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (String patientId : patientIds) {
            try {
                PatientMeasureData patient = fetchPatientData(patientId);
                if (patient != null) {
                    patients.add(patient);
                    successCount++;
                } else {
                    failCount++;
                    log.warn("Failed to fetch patient with ID: {}", patientId);
                }
            } catch (Exception e) {
                failCount++;
                log.error("Exception fetching patient ID {}: {}", patientId, e.getMessage(), e);
            }
        }

        log.info("Patient fetch completed: {} successful, {} failed, {} total",
                successCount, failCount, patients.size());

        return patients;
    }

    /**
     * Fetch personal details from EHR - matches QRDA package approach (fetchPatientPersonalDetails)
     */
    private PersonalDetailsData fetchPersonalDetails(String patientId, HttpEntity<Void> request) {
        try {

            String url = apiBaseUrl + "/personal-details?patient_id=" + patientId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Patient {} - Personal details API returned status: {}", patientId, response.getStatusCode());
                return null;
            }

            // Parse response using PersonalDetailsResponse DTO exactly like QRDA package approach
            PersonalDetailsResponse pdDTO = objectMapper.readValue(
                    response.getBody(),
                    PersonalDetailsResponse.class);

            if (pdDTO.getData() != null) {
                log.debug("Patient {} - Fetched personal details using QRDA package approach", patientId);
                return pdDTO.getData();
            }

            return null;
        } catch (Exception e) {
            log.error("Error fetching personal details for patient {}: {}", patientId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Fetch insurance details from EHR - matches QRDA package approach (fetchPatientInsuranceDetails)
     */
    private List<InsuranceDetails> fetchInsuranceDetails(String patientId, HttpEntity<Void> request) {
        try {
            
            String url = apiBaseUrl + "/insurance/cards?patient_id=" + patientId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("Patient {} - Insurance API returned status: {}", patientId, response.getStatusCode());
                return new ArrayList<>();
            }

            // Parse response using InsuranceDetailsResponse DTO exactly like QRDA package approach
            InsuranceDetailsResponse dto = objectMapper.readValue(
                    response.getBody(),
                    InsuranceDetailsResponse.class);

            if (dto.getData() == null || dto.getData().isEmpty()) {
                log.debug("Patient {} - No insurance details found", patientId);
                return new ArrayList<>();
            }

            log.info("Patient {} - Fetched {} insurance record(s) using QRDA package approach", 
                    patientId, dto.getData().size());
            return dto.getData();
            
        } catch (Exception e) {
            log.error("Error fetching insurance for patient {}: {}", patientId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private AppointmentData fetchAppointmentData(String patientId, String clinicId, HttpEntity<Void> request) {
        try {
            String startDate = "2013-01-01T00:00:00.000Z";
            String endDate = "2035-12-31T00:00:00.000Z";
            
            log.debug("Patient {} - Fetching appointments with date range: {} to {} (matching QRDA package approach)", 
                    patientId, startDate, endDate);

            String url = UriComponentsBuilder.fromUriString(apiBaseUrl)
                    .path("/appointment")
                    .queryParam("start_date", startDate)
                    .queryParam("end_date", endDate)
                    .queryParam("patient_id", patientId)
                    .queryParam("sort", "asc")
                    .queryParam("clinic_id", clinicId)
                    .toUriString();

            log.info("Fetching appointments for patient {} from URL: {} (using QRDA package approach)", patientId, url);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("Patient {} - Appointment API returned status: {}", 
                        patientId, response.getStatusCode());
                return new AppointmentData();
            }

            AppointmentResponse dto = objectMapper.readValue(response.getBody(), AppointmentResponse.class);
            
            if (dto == null) {
                log.warn("Patient {} - AppointmentResponse is null after parsing", patientId);
                return new AppointmentData();
            }
            
            AppointmentData appointmentData = dto.getData();
            if (appointmentData != null) {
                int appointmentCount = appointmentData.getAppointments() != null 
                        ? appointmentData.getAppointments().size() : 0;
                log.info("Patient {} - Fetched {} appointments from EHR using QRDA package approach (date range: {} to {})", 
                        patientId, appointmentCount, startDate, endDate);
                
                // Log first few appointments for debugging
                if (appointmentData.getAppointments() != null && !appointmentData.getAppointments().isEmpty()) {
                    appointmentData.getAppointments().stream()
                            .limit(3)
                            .forEach(apt -> {
                                log.debug("Patient {} - Appointment {}: date_time={}, end_date_time={}, type={}, status={}, category={}", 
                                        patientId, apt.getAppointment_id(), apt.getDate_time(), 
                                        apt.getEnd_date_time(), apt.getType(), apt.getAppointment_status(),
                                        apt.getCategory() != null ? apt.getCategory().size() + " categories" : "no categories");
                            });
                }
                
                return appointmentData;
            } else {
                log.warn("Patient {} - AppointmentResponse.data is null", patientId);
                return new AppointmentData();
            }

        } catch (Exception e) {
            log.error("Error fetching appointments for patient {}: {}", patientId, e.getMessage(), e);
            return new AppointmentData();
        }
    }

    /**
     * Fetch SOAP contexts and FormData from EHR - matches QRDA package approach (fetchSoapDetails)
     * This is the correct way to fetch assessments and interventions
     */
    private List<FormData> fetchSoapDetails(String patientId, HttpEntity<Void> request) {
        try {
            log.info("Patient {} - Starting SOAP details fetch (QRDA package approach)", patientId);

            // Step 1: Fetch SOAP contexts
            String soapUrl = apiBaseUrl + "/soap-context?patient_id=" + patientId;
            log.info("Patient {} - Fetching SOAP contexts from: {}", patientId, soapUrl);
            
            ResponseEntity<String> soapResponse = restTemplate.exchange(soapUrl, HttpMethod.GET, request, String.class);

            log.info("Patient {} - SOAP context API response - Status: {}, Body length: {}", 
                    patientId, soapResponse.getStatusCode(), 
                    soapResponse.getBody() != null ? soapResponse.getBody().length() : 0);

            if (!soapResponse.getStatusCode().is2xxSuccessful() || soapResponse.getBody() == null) {
                log.warn("Patient {} - SOAP context API returned status: {} or null body", 
                        patientId, soapResponse.getStatusCode());
                if (soapResponse.getBody() != null) {
                    log.warn("Patient {} - SOAP context API response body: {}", patientId, soapResponse.getBody());
                }
                return new ArrayList<>();
            }

            // Log raw response for debugging
            log.debug("Patient {} - SOAP context API raw response: {}", patientId, soapResponse.getBody());

            SoapContextResponse soapContextResponse = objectMapper.readValue(soapResponse.getBody(), SoapContextResponse.class);

            if (soapContextResponse == null) {
                log.warn("Patient {} - SoapContextResponse is null after parsing", patientId);
                return new ArrayList<>();
            }

            if (soapContextResponse.getData() == null) {
                log.warn("Patient {} - SoapContextResponse.data is null", patientId);
                return new ArrayList<>();
            }

            if (soapContextResponse.getData().getContexts() == null) {
                log.warn("Patient {} - SoapContextResponse.data.contexts is null", patientId);
                return new ArrayList<>();
            }

            int contextCount = soapContextResponse.getData().getContexts().size();
            log.info("Patient {} - Found {} SOAP context(s)", patientId, contextCount);

            if (contextCount == 0) {
                log.warn("Patient {} - No SOAP contexts found in response", patientId);
                return new ArrayList<>();
            }

            // Log details about each context
            for (int i = 0; i < soapContextResponse.getData().getContexts().size(); i++) {
                SoapContext context = soapContextResponse.getData().getContexts().get(i);
                log.info("Patient {} - Context {}: context_id={}, appointment_id={}, assessment_submission_id={}, " +
                        "subjective_submission_id={}, objective_submission_id={}", 
                        patientId, i + 1, 
                        context.getContextId(), 
                        context.getAppointmentId(),
                        context.getAssessmentSubmissionId(),
                        context.getSubjectiveSubmissionId(),
                        context.getObjectiveSubmissionId());
            }

            List<FormData> formDataList = new ArrayList<>();

            // Step 2: For each context with assessmentSubmissionId, fetch FormData
            // Note: Matching QRDA package approach - only check for null, not empty
            int contextsWithAssessment = 0;
            int formDataFetched = 0;
            for (SoapContext context : soapContextResponse.getData().getContexts()) {
                if (context.getAssessmentSubmissionId() != null) {
                    contextsWithAssessment++;
                    String assessmentSubmissionId = context.getAssessmentSubmissionId();
                    log.info("Patient {} - Found context with assessment_submission_id: {}", patientId, assessmentSubmissionId);
                    
                    String assessmentUrl = apiBaseUrl + "/form-data/" + assessmentSubmissionId;
                    log.debug("Patient {} - Fetching FormData from: {}", patientId, assessmentUrl);

                    ResponseEntity<String> assessmentResponse = restTemplate.exchange(assessmentUrl, HttpMethod.GET, request, String.class);

                    log.info("Patient {} - FormData API response for submission_id {} - Status: {}, Body length: {}", 
                            patientId, assessmentSubmissionId, assessmentResponse.getStatusCode(),
                            assessmentResponse.getBody() != null ? assessmentResponse.getBody().length() : 0);

                    if (assessmentResponse.getStatusCode().is2xxSuccessful() && assessmentResponse.getBody() != null) {
                        try {
                            FormDataResponse assessmentDto = objectMapper.readValue(assessmentResponse.getBody(), FormDataResponse.class);

                            if (assessmentDto != null && assessmentDto.getData() != null) {
                                FormData formData = FormData.builder()
                                        .submissionId(assessmentDto.getData().getSubmissionId())
                                        .patientId(assessmentDto.getData().getPatientId())
                                        .organisationId(assessmentDto.getData().getOrganisationId())
                                        .createdBy(assessmentDto.getData().getCreatedBy())
                                        .appointmentId(assessmentDto.getData().getAppointmentId())
                                        .formName(assessmentDto.getData().getFormName())
                                        .response(assessmentDto.getData().getResponse())
                                        .build();

                                formDataList.add(formData);
                                formDataFetched++;
                                
                                log.info("Patient {} - Successfully fetched FormData with submission_id: {}, form_name: {}, " +
                                        "has_response: {}, has_assessment: {}, has_intervention: {}", 
                                        patientId, formData.getSubmissionId(), formData.getFormName(),
                                        formData.getResponse() != null,
                                        formData.getResponse() != null && formData.getResponse().getAssessment() != null,
                                        formData.getResponse() != null && formData.getResponse().getIntervention() != null);
                            } else {
                                log.warn("Patient {} - FormDataResponse.data is null for submission_id: {}", 
                                        patientId, assessmentSubmissionId);
                            }
                        } catch (Exception e) {
                            log.error("Patient {} - Error parsing FormDataResponse for submission_id {}: {}", 
                                    patientId, assessmentSubmissionId, e.getMessage(), e);
                            log.error("Patient {} - FormDataResponse body: {}", patientId, assessmentResponse.getBody());
                        }
                    } else {
                        log.warn("Patient {} - FormData API failed for submission_id {} - Status: {}, Body: {}", 
                                patientId, assessmentSubmissionId, assessmentResponse.getStatusCode(),
                                assessmentResponse.getBody());
                    }
                } else {
                    log.debug("Patient {} - Context {} has no assessment_submission_id (context_id: {})", 
                            patientId, context.getContextId(), context.getContextId());
                }
            }

            log.info("Patient {} - SOAP details fetch complete: {} total contexts, {} with assessment_submission_id, {} FormData fetched", 
                    patientId, contextCount, contextsWithAssessment, formDataFetched);
            
            if (formDataList.isEmpty()) {
                log.warn("Patient {} - No FormData records fetched. This could mean:", patientId);
                log.warn("  1. No SOAP contexts have assessment_submission_id set");
                log.warn("  2. FormData API calls are failing");
                log.warn("  3. FormDataResponse.data is null");
            }

            return formDataList;

        } catch (Exception e) {
            log.error("Patient {} - Error fetching SOAP details: {}", patientId, e.getMessage(), e);
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    /**
     * Extract FormResponse objects from FormData
     * Stores FormResponse directly instead of extracting to AssessmentData/InterventionData
     */
    private List<FormResponse> extractFormResponses(List<FormData> formDataList) {
        List<FormResponse> formResponses = new ArrayList<>();

        if (formDataList == null || formDataList.isEmpty()) {
            return formResponses;
        }

        for (FormData formData : formDataList) {
            if (formData == null || formData.getResponse() == null) {
                continue;
            }

            FormResponse response = formData.getResponse();
            formResponses.add(response);
        }

        log.info("Extracted {} FormResponse(s) from {} FormData record(s)", formResponses.size(), formDataList.size());
        return formResponses;
    }
}

