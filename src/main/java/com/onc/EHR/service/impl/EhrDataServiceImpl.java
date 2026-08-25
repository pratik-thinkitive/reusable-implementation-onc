package com.onc.EHR.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onc.EHR.dto.*;
import com.onc.EHR.service.EhrDataService;
import com.onc.EHR.service.EhrTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place this application talks to the EHR provider API.
 *
 * <p>Consolidates two prior implementations: QRDA's six patient reads and G2's copy of the
 * same six plus three directory reads. Behaviour of each method is carried over unchanged -
 * including the differences between them, such as which return {@code noContent} on an empty
 * result - so both modules see exactly the responses they saw before.
 *
 * <p>Every URL is now built from {@code ehr.api.base-url}. The G2 copy had the provider
 * hostname hardcoded in eight of its methods.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EhrDataServiceImpl implements EhrDataService {

    @Value("${ehr.api.base-url}")
    private String apiBaseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final EhrTokenService tokenService;

    /** Builds the authenticated GET entity every read shares. */
    private HttpEntity<Void> authorizedRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenService.getAccessToken());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    private ResponseEntity<String> get(String url) {
        return restTemplate.exchange(url, HttpMethod.GET, authorizedRequest(), String.class);
    }

    // ---------------------------------------------------------------- patient reads

    @Override
    public ResponseEntity<MedicalDetailsData> fetchPatientMedicalDetails(String fhirId) {
        try {
            String patientId = extractPatientId(fhirId);
            if (patientId == null) {
                return ResponseEntity.badRequest().body(null);
            }

            ResponseEntity<String> response = get(apiBaseUrl + "/medical-details?patient_id=" + patientId);

            if (!response.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.status(response.getStatusCode()).body(null);
            }

            MedicalDetailsResponse dto = objectMapper.readValue(response.getBody(), MedicalDetailsResponse.class);
            return ResponseEntity.ok(dto.getData());

        } catch (Exception e) {
            log.error("Failed to fetch medical details", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @Override
    public ResponseEntity<PersonalDetailsData> fetchPatientPersonalDetails(String fhirId) {
        try {
            String patientId = extractPatientId(fhirId);
            if (patientId == null) {
                return ResponseEntity.badRequest().body(null);
            }

            ResponseEntity<String> response = get(apiBaseUrl + "/personal-details?patient_id=" + patientId);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return ResponseEntity.status(response.getStatusCode()).body(null);
            }

            PersonalDetailsResponse dto = objectMapper.readValue(response.getBody(), PersonalDetailsResponse.class);
            return ResponseEntity.ok(dto.getData());

        } catch (Exception e) {
            log.error("Failed to fetch personal details", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @Override
    public ResponseEntity<List<InsuranceDetails>> fetchPatientInsuranceDetails(String fhirId) {
        try {
            String patientId = extractPatientId(fhirId);
            if (patientId == null) {
                return ResponseEntity.badRequest().build();
            }

            ResponseEntity<String> response = get(apiBaseUrl + "/insurance/cards?patient_id=" + patientId);

            if (!response.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.status(response.getStatusCode()).build();
            }

            InsuranceDetailsResponse dto = objectMapper.readValue(response.getBody(), InsuranceDetailsResponse.class);

            if (dto.getData() == null || dto.getData().isEmpty()) {
                return ResponseEntity.noContent().build();
            }

            return ResponseEntity.ok(dto.getData());

        } catch (Exception e) {
            log.error("Failed to fetch insurance details", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @Override
    public ResponseEntity<AppointmentData> fetchAppointments(String fhirId, String clinicId) {
        try {
            String patientId = extractPatientId(fhirId);
            if (patientId == null) {
                return ResponseEntity.badRequest().body(null);
            }

            String url = UriComponentsBuilder.fromUriString(apiBaseUrl)
                    .path("/appointment")
                    .queryParam("start_date", "2023-01-01T00:00:00.000Z")
                    .queryParam("end_date", "2025-12-31T00:00:00.000Z")
                    .queryParam("patient_id", patientId)
                    .queryParam("sort", "asc")
                    .queryParam("clinic_id", clinicId)
                    .toUriString();

            ResponseEntity<String> response = get(url);

            if (!response.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.status(response.getStatusCode()).body(null);
            }

            AppointmentResponse dto = objectMapper.readValue(response.getBody(), AppointmentResponse.class);
            return ResponseEntity.ok(dto.getData());

        } catch (Exception e) {
            log.error("Failed to fetch appointments", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @Override
    public ResponseEntity<DoctorDetailsData> fetchDoctorDetails(int doctorId) {
        try {
            ResponseEntity<String> response = get(apiBaseUrl + "/doctor/" + doctorId);

            if (!response.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.status(response.getStatusCode()).body(null);
            }

            DoctorDetailsResponse dto = objectMapper.readValue(response.getBody(), DoctorDetailsResponse.class);
            return ResponseEntity.ok(dto.getData());

        } catch (Exception e) {
            log.error("Failed to fetch doctor details", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @Override
    public ResponseEntity<List<FormData>> fetchSoapDetails(String fhirId) {
        ResponseEntity<String> response = null;
        try {
            String patientId = extractPatientId(fhirId);
            if (patientId == null) {
                return ResponseEntity.badRequest().body(null);
            }

            response = get(apiBaseUrl + "/soap-context?patient_id=" + patientId);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return ResponseEntity.status(response.getStatusCode()).body(null);
            }

            SoapContextResponse soapContextResponse =
                    objectMapper.readValue(response.getBody(), SoapContextResponse.class);

            if (soapContextResponse.getData() == null || soapContextResponse.getData().getContexts().isEmpty()) {
                return ResponseEntity.noContent().build();
            }

            List<FormData> assessments = new ArrayList<>();

            for (SoapContext context : soapContextResponse.getData().getContexts()) {
                if (context.getAssessmentSubmissionId() == null) {
                    continue;
                }

                ResponseEntity<String> assessmentResponse =
                        get(apiBaseUrl + "/form-data/" + context.getAssessmentSubmissionId());

                if (!assessmentResponse.getStatusCode().is2xxSuccessful() || assessmentResponse.getBody() == null) {
                    continue;
                }

                FormDataResponse assessmentDto =
                        objectMapper.readValue(assessmentResponse.getBody(), FormDataResponse.class);

                if (assessmentDto.getData() == null) {
                    continue;
                }

                FormData details = assessmentDto.getData();
                assessments.add(FormData.builder()
                        .submissionId(details.getSubmissionId())
                        .patientId(details.getPatientId())
                        .organisationId(details.getOrganisationId())
                        .createdBy(details.getCreatedBy())
                        .appointmentId(details.getAppointmentId())
                        .formName(details.getFormName())
                        .response(details.getResponse())
                        .build());
            }

            if (assessments.isEmpty()) {
                return ResponseEntity.noContent().build();
            }

            return ResponseEntity.ok(assessments);

        } catch (Exception e) {
            log.error("Failed to fetch SOAP details", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @Override
    public String extractPatientId(String patientFhirId) {
        if (patientFhirId == null || !patientFhirId.contains("-")) {
            return null;
        }
        String[] parts = patientFhirId.split("-");
        return parts.length > 1 ? parts[1] : null;
    }
}
