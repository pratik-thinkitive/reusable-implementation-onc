package com.onc.EHR.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onc.EHR.dto.*;
import com.onc.EHR.service.EHRDataService;
import com.onc.EHR.service.EHRTokenService;
import com.onc.api.support.ResponseCode;
import com.onc.common.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The one place this application talks to the EHR provider API.
 *
 * <p>Consolidates two prior implementations: QRDA's six patient reads and G2's copy of the
 * same six plus three directory reads.
 *
 * <p>Failures leave through {@link AppException} rather than a status-carrying
 * {@code ResponseEntity}, so no caller has to inspect a status code to find out whether it got
 * data. The upstream status and body never reach the client - the body carries PHI, and the
 * status would tell a caller about the provider rather than about their own request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EHRDataServiceImpl implements EHRDataService {

    private static final String UPSTREAM_MESSAGE =
            "The EHR provider could not be reached. Please try again later.";

    private static final String BAD_FHIR_ID =
            "'fhirId' must be a composite id of the form organisation-patient.";

    @Value("${ehr.api.base-url}")
    private String apiBaseUrl;

    /** Organisation whose clinics are enumerated when listing providers. */
    @Value("${ehr.organisation-id:0}")
    private int defaultOrganisationId;

    /** Page size used when listing providers for a clinic. */
    @Value("${ehr.provider-page-size:50}")
    private int providerPageSize;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final EHRTokenService tokenService;

    /** Builds the authenticated GET entity every read shares. */
    private HttpEntity<Void> authorizedRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenService.getAccessToken());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    /** Performs the read and fails unless it came back 2xx with a body. */
    private String get(String url, String what) {
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, authorizedRequest(), String.class);
        } catch (Exception e) {
            throw upstream(what, e);
        }

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.error("EHR provider returned {} for {}", response.getStatusCode(), what);
            throw new AppException(ResponseCode.UPSTREAM_UNAVAILABLE, UPSTREAM_MESSAGE);
        }

        return response.getBody();
    }

    /** Reads and deserializes in one step; a malformed payload is an upstream failure too. */
    private <T> T get(String url, String what, Class<T> type) {
        String body = get(url, what);
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw upstream(what, e);
        }
    }

    private AppException upstream(String what, Exception cause) {
        log.error("Failed to fetch {}", what, cause);
        return new AppException(ResponseCode.UPSTREAM_UNAVAILABLE, UPSTREAM_MESSAGE, cause);
    }

    /** Every patient read starts here; an id the provider cannot use is the caller's error. */
    private String requirePatientId(String fhirId) {
        String patientId = extractPatientId(fhirId);
        if (patientId == null) {
            throw new AppException(ResponseCode.BAD_REQUEST, BAD_FHIR_ID, "fhirId");
        }
        return patientId;
    }

    // ---------------------------------------------------------------- patient reads

    @Override
    public MedicalDetailsData fetchPatientMedicalDetails(String fhirId) {
        String patientId = requirePatientId(fhirId);
        return get(apiBaseUrl + "/medical-details?patient_id=" + patientId,
                "medical details", MedicalDetailsResponse.class).getData();
    }

    @Override
    public PersonalDetailsData fetchPatientPersonalDetails(String fhirId) {
        String patientId = requirePatientId(fhirId);
        return get(apiBaseUrl + "/personal-details?patient_id=" + patientId,
                "personal details", PersonalDetailsResponse.class).getData();
    }

    @Override
    public List<InsuranceDetails> fetchPatientInsuranceDetails(String fhirId) {
        String patientId = requirePatientId(fhirId);
        InsuranceDetailsResponse dto = get(apiBaseUrl + "/insurance/cards?patient_id=" + patientId,
                "insurance details", InsuranceDetailsResponse.class);

        return dto.getData() == null ? Collections.emptyList() : dto.getData();
    }

    @Override
    public AppointmentData fetchAppointments(String fhirId, String clinicId) {
        String patientId = requirePatientId(fhirId);

        String url = UriComponentsBuilder.fromUriString(apiBaseUrl)
                .path("/appointment")
                .queryParam("start_date", "2023-01-01T00:00:00.000Z")
                .queryParam("end_date", "2025-12-31T00:00:00.000Z")
                .queryParam("patient_id", patientId)
                .queryParam("sort", "asc")
                .queryParam("clinic_id", clinicId)
                .toUriString();

        return get(url, "appointments", AppointmentResponse.class).getData();
    }

    @Override
    public DoctorDetailsData fetchDoctorDetails(int doctorId) {
        DoctorDetailsData data = get(apiBaseUrl + "/doctor/" + doctorId,
                "doctor details", DoctorDetailsResponse.class).getData();

        if (data == null) {
            throw new AppException(ResponseCode.NOT_FOUND, "No provider found for id " + doctorId + ".");
        }
        return data;
    }

    @Override
    public List<FormData> fetchSoapDetails(String fhirId) {
        String patientId = requirePatientId(fhirId);

        SoapContextResponse contexts = get(apiBaseUrl + "/soap-context?patient_id=" + patientId,
                "SOAP context", SoapContextResponse.class);

        if (contexts.getData() == null || contexts.getData().getContexts() == null) {
            return Collections.emptyList();
        }

        List<FormData> assessments = new ArrayList<>();

        for (SoapContext context : contexts.getData().getContexts()) {
            if (context.getAssessmentSubmissionId() == null) {
                continue;
            }

            FormData details = fetchAssessment(context.getAssessmentSubmissionId());
            if (details == null) {
                continue;
            }

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

        return assessments;
    }

    /**
     * One assessment out of the fan-out. A single failed form is skipped rather than failing the
     * whole read, which is what the previous per-context status check did.
     */
    private FormData fetchAssessment(String submissionId) {
        try {
            return get(apiBaseUrl + "/form-data/" + submissionId,
                    "form data", FormDataResponse.class).getData();
        } catch (AppException e) {
            log.warn("Skipping assessment {} - it could not be read", submissionId);
            return null;
        }
    }

    // ---------------------------------------------------------------- directory reads

    @Override
    public Clinic fetchClinicDetails(int clinicId) {
        Clinic clinic = get(apiBaseUrl + "/clinic/" + clinicId,
                "clinic details", ClinicResponse.class).getData();

        if (clinic == null) {
            throw new AppException(ResponseCode.NOT_FOUND, "No clinic found for id " + clinicId + ".");
        }
        return clinic;
    }

    @Override
    public List<Clinic> fetchAllClinicsByOrganisationId(int organisationId) {
        if (organisationId <= 0) {
            throw new AppException(
                    ResponseCode.BAD_REQUEST, "'organisationId' must be positive.", "organisationId");
        }

        ClinicListResponse response = get(
                apiBaseUrl + "/branch/organisation/" + organisationId + "/get-all-clinics",
                "clinics", ClinicListResponse.class);

        if (response.getData() == null || response.getData().getClinics() == null) {
            return Collections.emptyList();
        }
        return response.getData().getClinics();
    }

    /**
     * Lists providers across the configured organisation's clinics, de-duplicated by doctor id.
     *
     * <p>Note the parameter is used only for logging: the original implementation enumerated
     * every clinic in the organisation rather than the one requested, and that behaviour is
     * carried over unchanged. The organisation is configuration rather than a literal.
     */
    @Override
    public List<DoctorDetailsData> fetchAllDoctorsByClinicId(String clinicId) {
        if (clinicId == null || clinicId.isBlank()) {
            throw new AppException(ResponseCode.BAD_REQUEST, "'clinicId' is required.", "clinicId");
        }

        List<Clinic> allClinics = fetchAllClinicsByOrganisationId(defaultOrganisationId);

        if (allClinics.isEmpty()) {
            log.warn("No clinics found for organisationId {}", defaultOrganisationId);
            return Collections.emptyList();
        }

        List<DoctorDetailsData> allDoctors = new ArrayList<>();

        for (Clinic clinic : allClinics) {
            String currentClinicId = String.valueOf(clinic.getClinic_id());
            String url = UriComponentsBuilder.fromUriString(apiBaseUrl)
                    .path("/doctor")
                    .queryParam("clinicId", currentClinicId)
                    .queryParam("size", providerPageSize)
                    .toUriString();

            try {
                DoctorListResponse response = get(url, "providers", DoctorListResponse.class);
                if (response.getData() != null && response.getData().getDoctors() != null) {
                    allDoctors.addAll(response.getData().getDoctors());
                }
            } catch (AppException e) {
                // One unreachable clinic must not empty the whole directory listing.
                log.warn("Skipping clinic {} - its providers could not be read", currentClinicId);
            }
        }

        return allDoctors.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(DoctorDetailsData::getDoctor_id, d -> d, (first, duplicate) -> first),
                        map -> new ArrayList<>(map.values())));
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
