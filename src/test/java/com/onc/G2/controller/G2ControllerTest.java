package com.onc.G2.controller;

import com.onc.EHR.dto.Clinic;
import com.onc.EHR.dto.DoctorDetailsData;
import com.onc.EHR.dto.PatientInformation;
import com.onc.EHR.dto.PersonalDetailsData;
import com.onc.EHR.dto.PersonalDetailsResponseBlock;
import com.onc.EHR.service.EHRDataService;
import com.onc.G2.dto.PatientAccessRequestDto;
import com.onc.G2.enums.RequestType;
import com.onc.G2.service.PatientAccessDataService;
import com.onc.G2.service.PatientAccessRequestService;
import com.onc.config.ConfigurationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Characterization tests for {@link G2Controller}.
 *
 * <p>A characterization test does not say what the code <em>should</em> do. It records what the
 * code <em>currently</em> does, so that a refactor which accidentally changes behaviour makes a
 * test go red. Read these as "this is the contract our callers see today".
 *
 * <p>Every collaborator is mocked, so nothing here touches a database or the upstream EHR API.
 * {@code @Import(ConfigurationService.class)} pulls in the application's real bean definitions so
 * the test context resembles production rather than a bare test slice.
 */
@WebMvcTest(G2Controller.class)
@Import(ConfigurationService.class)
class G2ControllerTest {

    private static final String PERSONAL_DETAILS_URL = "/ehr/g2/personal-details";
    private static final String REQUEST_ACCESS_URL = "/ehr/g2/request-access";

    /** Composite FHIR id of the form {@code organisation-patient}. */
    private static final String FHIR_ID = "12-3456";

    /** The controller derives this from FHIR_ID by splitting on "-" and taking the second part. */
    private static final String PATIENT_ID = "3456";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EHRDataService ehrDataService;

    @MockitoBean
    private PatientAccessRequestService patientAccessRequestService;

    @MockitoBean
    private PatientAccessDataService patientAccessDataService;

    // ------------------------------------------------------------------ GET /personal-details

    @Nested
    @DisplayName("GET /ehr/g2/personal-details")
    class FetchPersonalDetails {

        @Test
        @DisplayName("with active access: returns the EHR payload and records the numerator")
        void returnsDetailsWhenAccessIsActive() throws Exception {
            when(patientAccessRequestService.hasActiveAccess(FHIR_ID, RequestType.MEDICAL_DETAILS_ACCESS))
                    .thenReturn(true);
            when(ehrDataService.fetchPatientPersonalDetails(FHIR_ID))
                    .thenReturn(ResponseEntity.ok(personalDetails()));

            mockMvc.perform(get(PERSONAL_DETAILS_URL).param("fhirId", FHIR_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.organisation_id").value(7))
                    .andExpect(jsonPath("$.created_by").value(42));

            // Reading the data is what puts the patient in the numerator. The reporting period
            // defaults to the current calendar year, and hasAccess is always passed as true here.
            verify(patientAccessDataService).updateNumerator(
                    eq(FHIR_ID),
                    eq(LocalDate.now().withDayOfYear(1)),
                    eq(LocalDate.now().withMonth(12).withDayOfMonth(31)),
                    eq(true),
                    any(Instant.class));
        }

        @Test
        @DisplayName("without active access: returns 403 with the fixed advisory body")
        void returnsForbiddenWhenAccessIsNotActive() throws Exception {
            when(patientAccessRequestService.hasActiveAccess(FHIR_ID, RequestType.MEDICAL_DETAILS_ACCESS))
                    .thenReturn(false);

            mockMvc.perform(get(PERSONAL_DETAILS_URL).param("fhirId", FHIR_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.accessGranted").value(false))
                    .andExpect(jsonPath("$.requestType").value("MEDICAL_DETAILS_ACCESS"))
                    .andExpect(jsonPath("$.message").value(
                            "You do not currently have access to view your health information. "
                                    + "Please request access for it."));

            // No access means the patient must not be counted.
            verify(patientAccessDataService, never())
                    .updateNumerator(anyString(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
            // The chart is never fetched either.
            verify(ehrDataService, never()).fetchPatientPersonalDetails(anyString());
        }

        @Test
        @DisplayName("when a collaborator throws: returns 500 with a plain-text body")
        void returnsServerErrorWhenSomethingThrows() throws Exception {
            when(patientAccessRequestService.hasActiveAccess(anyString(), any()))
                    .thenThrow(new RuntimeException("boom"));

            String body = mockMvc.perform(get(PERSONAL_DETAILS_URL).param("fhirId", FHIR_ID))
                    .andExpect(status().isInternalServerError())
                    .andReturn().getResponse().getContentAsString();

            // Note this endpoint returns a bare String, unlike every other error path in the module.
            assertThat(body).isEqualTo("Error processing request");
        }
    }

    // ------------------------------------------------------------------ POST /request-access

    @Nested
    @DisplayName("POST /ehr/g2/request-access")
    class RequestAccess {

        @Test
        @DisplayName("new request: creates it, seeds the data row and counts the encounter")
        void createsRequest() throws Exception {
            stubEhrLookups();
            when(patientAccessRequestService.createAccessRequest(
                    anyString(), anyString(), anyString(), anyString(), any(),
                    anyString(), anyString(), any(), anyString(), any(), any(), any()))
                    .thenReturn(newRequestDto(55L, false));

            mockMvc.perform(post(REQUEST_ACCESS_URL)
                            .param("fhirId", FHIR_ID)
                            .param("requestType", "medical_details_access")
                            .param("encounterId", "enc-1")
                            .param("providerId", "prov-9")
                            .param("tinId", "tin-9"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.requestId").value("55"));

            LocalDate expectedStart = LocalDate.now().withDayOfYear(1);
            LocalDate expectedEnd = LocalDate.now().withMonth(12).withDayOfMonth(31);

            // requestType is accepted case-insensitively and upper-cased before valueOf.
            verify(patientAccessRequestService).createAccessRequest(
                    eq(FHIR_ID), eq(PATIENT_ID), eq("Ada"), eq("Lovelace"), eq(7),
                    eq("prov-9"), eq("tin-9"), eq(RequestType.MEDICAL_DETAILS_ACCESS), eq("enc-1"),
                    eq(null), eq(expectedStart), eq(expectedEnd));

            // The provider and TIN written to the data row come from the REQUEST PARAMETERS,
            // not from the values the controller derives from the EHR. Locking this in so the
            // refactor cannot change attribution by accident.
            verify(patientAccessDataService).initializePatientData(
                    eq(FHIR_ID), eq(PATIENT_ID), eq("Ada"), eq("Lovelace"), eq(7),
                    eq("prov-9"), eq("tin-9"), eq(expectedStart), eq(expectedEnd));

            verify(patientAccessDataService)
                    .updateDenominator(eq(FHIR_ID), eq(expectedStart), eq(expectedEnd), any(Instant.class));
        }

        @Test
        @DisplayName("explicit reporting period: passes the supplied dates straight through")
        void honoursSuppliedReportingPeriod() throws Exception {
            stubEhrLookups();
            when(patientAccessRequestService.createAccessRequest(
                    anyString(), anyString(), anyString(), anyString(), any(),
                    anyString(), anyString(), any(), anyString(), any(), any(), any()))
                    .thenReturn(newRequestDto(56L, false));

            mockMvc.perform(post(REQUEST_ACCESS_URL)
                            .param("fhirId", FHIR_ID)
                            .param("requestType", "MEDICAL_DETAILS_ACCESS")
                            .param("encounterId", "enc-2")
                            .param("providerId", "prov-9")
                            .param("tinId", "tin-9")
                            .param("reportingPeriodStart", "2026-02-01")
                            .param("reportingPeriodEnd", "2026-11-30"))
                    .andExpect(status().isOk());

            verify(patientAccessDataService).updateDenominator(
                    eq(FHIR_ID),
                    eq(LocalDate.of(2026, 2, 1)),
                    eq(LocalDate.of(2026, 11, 30)),
                    any(Instant.class));
        }

        @Test
        @DisplayName("duplicate request: returns 409 and skips all counter updates")
        void returnsConflictForDuplicate() throws Exception {
            stubEhrLookups();
            PatientAccessRequestDto duplicate = newRequestDto(77L, true);
            duplicate.setDuplicateMessage("You already have access to the health information from your prior appointments.");
            when(patientAccessRequestService.createAccessRequest(
                    anyString(), anyString(), anyString(), anyString(), any(),
                    anyString(), anyString(), any(), anyString(), any(), any(), any()))
                    .thenReturn(duplicate);

            mockMvc.perform(post(REQUEST_ACCESS_URL)
                            .param("fhirId", FHIR_ID)
                            .param("requestType", "MEDICAL_DETAILS_ACCESS")
                            .param("encounterId", "enc-3")
                            .param("providerId", "prov-9")
                            .param("tinId", "tin-9"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value("DUPLICATE"))
                    .andExpect(jsonPath("$.requestId").value("77"))
                    .andExpect(jsonPath("$.message").value(
                            "You already have access to the health information from your prior appointments."));

            // A duplicate must not seed a data row or move the denominator.
            verify(patientAccessDataService, never())
                    .initializePatientData(anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyString(), any(), any());
            verify(patientAccessDataService, never())
                    .updateDenominator(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("unknown requestType: currently answers 500, not 400")
        void returnsServerErrorForUnknownRequestType() throws Exception {
            mockMvc.perform(post(REQUEST_ACCESS_URL)
                            .param("fhirId", FHIR_ID)
                            .param("requestType", "NOT_A_REAL_TYPE")
                            .param("encounterId", "enc-4")
                            .param("providerId", "prov-9")
                            .param("tinId", "tin-9"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    // The enum name leaks into the message via IllegalArgumentException.
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.startsWith("Error creating access request:")));
        }

        @Test
        @DisplayName("unparseable reporting period: currently answers 500, not 400")
        void returnsServerErrorForBadDate() throws Exception {
            mockMvc.perform(post(REQUEST_ACCESS_URL)
                            .param("fhirId", FHIR_ID)
                            .param("requestType", "MEDICAL_DETAILS_ACCESS")
                            .param("encounterId", "enc-5")
                            .param("providerId", "prov-9")
                            .param("tinId", "tin-9")
                            .param("reportingPeriodStart", "not-a-date"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("EHR lookup failure: still creates the request, with null patient names")
        void toleratesEhrFailure() throws Exception {
            // A non-2xx personal-details response makes the controller fall back to an empty
            // PatientDetails, so names and organisation land as null rather than aborting.
            when(ehrDataService.fetchPatientPersonalDetails(FHIR_ID))
                    .thenReturn(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(null));
            when(patientAccessRequestService.createAccessRequest(
                    anyString(), anyString(), any(), any(), any(),
                    anyString(), anyString(), any(), anyString(), any(), any(), any()))
                    .thenReturn(newRequestDto(88L, false));

            mockMvc.perform(post(REQUEST_ACCESS_URL)
                            .param("fhirId", FHIR_ID)
                            .param("requestType", "MEDICAL_DETAILS_ACCESS")
                            .param("encounterId", "enc-6")
                            .param("providerId", "prov-9")
                            .param("tinId", "tin-9"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(patientAccessRequestService).createAccessRequest(
                    eq(FHIR_ID), eq(PATIENT_ID), eq(null), eq(null), eq(null),
                    eq("prov-9"), eq("tin-9"), any(), eq("enc-6"), eq(null), any(), any());
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** Stubs the three-step EHR walk: personal details, then doctor, then clinic. */
    private void stubEhrLookups() {
        when(ehrDataService.fetchPatientPersonalDetails(FHIR_ID))
                .thenReturn(ResponseEntity.ok(personalDetails()));

        Clinic clinic = new Clinic();
        clinic.setClinic_id(101);
        clinic.setTax_identification_number("TIN-FROM-EHR");

        DoctorDetailsData doctor = new DoctorDetailsData();
        doctor.setDoctor_id(42);
        doctor.setClinics(List.of(clinic));

        when(ehrDataService.fetchDoctorDetails(anyInt())).thenReturn(ResponseEntity.ok(doctor));
        when(ehrDataService.fetchClinicDetails(anyInt())).thenReturn(ResponseEntity.ok(clinic));
    }

    private PersonalDetailsData personalDetails() {
        PatientInformation info = new PatientInformation();
        info.setFirstName("Ada");
        info.setLastName("Lovelace");

        PersonalDetailsResponseBlock block = new PersonalDetailsResponseBlock();
        block.setPatientInformation(Map.of("patient", info));

        PersonalDetailsData data = new PersonalDetailsData();
        data.setOrganisationId(7);
        data.setCreatedBy(42);
        data.setResponse(block);
        return data;
    }

    private PatientAccessRequestDto newRequestDto(Long id, boolean duplicate) {
        PatientAccessRequestDto dto = new PatientAccessRequestDto();
        dto.setId(id);
        dto.setDuplicateRequest(duplicate);
        return dto;
    }
}
