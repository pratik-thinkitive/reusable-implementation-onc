package com.onc.G2.controller;

import com.onc.EHR.dto.Clinic;
import com.onc.EHR.dto.DoctorDetailsData;
import com.onc.EHR.dto.PatientInformation;
import com.onc.EHR.dto.PersonalDetailsData;
import com.onc.EHR.dto.PersonalDetailsResponseBlock;
import com.onc.EHR.service.EHRDataService;
import com.onc.G2.converter.StringToRequestTypeConverter;
import com.onc.G2.dto.PatientAccessRequestDto;
import com.onc.G2.enums.RequestType;
import com.onc.G2.service.PatientAccessDataService;
import com.onc.G2.service.PatientAccessRequestService;
import com.onc.G2.service.impl.PatientAccessWorkflowServiceImpl;
import com.onc.G2.service.impl.PatientAttributionServiceImpl;
import com.onc.api.support.ResponseCode;
import com.onc.common.exception.AppException;
import com.onc.config.ConfigurationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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
 * Locks in what these endpoints answer. Only the leaf services are mocked, so the real
 * orchestration runs and a behaviour change shows up here.
 */
@WebMvcTest(G2Controller.class)
@Import({ConfigurationService.class,
        StringToRequestTypeConverter.class,
        PatientAccessWorkflowServiceImpl.class,
        PatientAttributionServiceImpl.class})
class G2ControllerTest {

    private static final String PERSONAL_DETAILS_URL = "/ehr/g2/personal-details";
    private static final String REQUEST_ACCESS_URL = "/ehr/g2/request-access";

    /** Composite FHIR id of the form {@code organisation-patient}. */
    private static final String FHIR_ID = "12-3456";

    /** The part of FHIR_ID after the dash. */
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
            when(ehrDataService.fetchPatientPersonalDetails(FHIR_ID)).thenReturn(personalDetails());

            mockMvc.perform(get(PERSONAL_DETAILS_URL).param("fhirId", FHIR_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.organisation_id").value(7))
                    .andExpect(jsonPath("$.data.created_by").value(42));

            // Reading the data is what puts the patient in the numerator.
            verify(patientAccessDataService).updateNumerator(
                    eq(FHIR_ID),
                    eq(LocalDate.now().withDayOfYear(1)),
                    eq(LocalDate.now().withMonth(12).withDayOfMonth(31)),
                    eq(true),
                    any(Instant.class));
        }

        @Test
        @DisplayName("without active access: 403 with the advisory message in the envelope")
        void returnsForbiddenWhenAccessIsNotActive() throws Exception {
            when(patientAccessRequestService.hasActiveAccess(FHIR_ID, RequestType.MEDICAL_DETAILS_ACCESS))
                    .thenReturn(false);

            mockMvc.perform(get(PERSONAL_DETAILS_URL).param("fhirId", FHIR_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("PATIENT_ACCESS_DENIED"))
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andExpect(jsonPath("$.message").value(
                            "You do not currently have access to view your health information. "
                                    + "Please request access for it."));

            // No access means neither counting the patient nor fetching the chart.
            verify(patientAccessDataService, never())
                    .updateNumerator(anyString(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
            verify(ehrDataService, never()).fetchPatientPersonalDetails(anyString());
        }

        @Test
        @DisplayName("when a collaborator throws: 500 in the envelope, cause not echoed")
        void returnsServerErrorWhenSomethingThrows() throws Exception {
            when(patientAccessRequestService.hasActiveAccess(anyString(), any()))
                    .thenThrow(new RuntimeException("boom"));

            String body = mockMvc.perform(get(PERSONAL_DETAILS_URL).param("fhirId", FHIR_ID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("Something went wrong. Please try again later."))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("boom");
        }

        @Test
        @DisplayName("an unreachable EHR surfaces as 502, not as a success with no data")
        void upstreamFailureIsBadGateway() throws Exception {
            when(patientAccessRequestService.hasActiveAccess(FHIR_ID, RequestType.MEDICAL_DETAILS_ACCESS))
                    .thenReturn(true);
            when(ehrDataService.fetchPatientPersonalDetails(FHIR_ID)).thenThrow(new AppException(
                    ResponseCode.UPSTREAM_UNAVAILABLE,
                    "The EHR provider could not be reached. Please try again later."));

            mockMvc.perform(get(PERSONAL_DETAILS_URL).param("fhirId", FHIR_ID))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
        }
    }

    // ------------------------------------------------------------------ POST /request-access

    @Nested
    @DisplayName("POST /ehr/g2/request-access")
    class RequestAccess {

        @Test
        @DisplayName("new request: 201, seeds the data row and counts the encounter")
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
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value("CREATED"))
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andExpect(jsonPath("$.data.requestId").value("55"));

            LocalDate expectedStart = LocalDate.now().withDayOfYear(1);
            LocalDate expectedEnd = LocalDate.now().withMonth(12).withDayOfMonth(31);

            // requestType is still accepted case-insensitively, now via a registered converter.
            verify(patientAccessRequestService).createAccessRequest(
                    eq(FHIR_ID), eq(PATIENT_ID), eq("Ada"), eq("Lovelace"), eq(7),
                    eq("prov-9"), eq("tin-9"), eq(RequestType.MEDICAL_DETAILS_ACCESS), eq("enc-1"),
                    eq(null), eq(expectedStart), eq(expectedEnd));

            // Provider and TIN come from the request parameters, not the EHR-derived values.
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
                    .andExpect(status().isCreated());

            verify(patientAccessDataService).updateDenominator(
                    eq(FHIR_ID),
                    eq(LocalDate.of(2026, 2, 1)),
                    eq(LocalDate.of(2026, 11, 30)),
                    any(Instant.class));
        }

        @Test
        @DisplayName("duplicate request: 409 carrying the blocking request's id, no counter updates")
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
                    .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"))
                    .andExpect(jsonPath("$.data.status").value("DUPLICATE"))
                    .andExpect(jsonPath("$.data.requestId").value("77"))
                    .andExpect(jsonPath("$.message").value(
                            "You already have access to the health information from your prior appointments."));

            // A duplicate must not seed a data row or move the denominator.
            verify(patientAccessDataService, never())
                    .initializePatientData(anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyString(), any(), any());
            verify(patientAccessDataService, never())
                    .updateDenominator(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("unknown requestType: 400 listing the allowed values")
        void rejectsUnknownRequestType() throws Exception {
            mockMvc.perform(post(REQUEST_ACCESS_URL)
                            .param("fhirId", FHIR_ID)
                            .param("requestType", "NOT_A_REAL_TYPE")
                            .param("encounterId", "enc-4")
                            .param("providerId", "prov-9")
                            .param("tinId", "tin-9"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                            "Invalid value for 'requestType'. Allowed: "
                                    + "MEDICAL_DETAILS_ACCESS, PERSONAL_DETAILS_ACCESS."));

            verify(patientAccessRequestService, never()).createAccessRequest(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("unparseable reporting period: 400 naming the parameter and the format")
        void rejectsBadDate() throws Exception {
            mockMvc.perform(post(REQUEST_ACCESS_URL)
                            .param("fhirId", FHIR_ID)
                            .param("requestType", "MEDICAL_DETAILS_ACCESS")
                            .param("encounterId", "enc-5")
                            .param("providerId", "prov-9")
                            .param("tinId", "tin-9")
                            .param("reportingPeriodStart", "not-a-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                            "'reportingPeriodStart' must be a valid date in yyyy-MM-dd format."))
                    .andExpect(jsonPath("$.errors.reportingPeriodStart").exists());
        }

        @Test
        @DisplayName("EHR lookup failure: still creates the request, with null patient names")
        void toleratesEhrFailure() throws Exception {
            // A failed lookup falls back to empty attribution rather than aborting.
            when(ehrDataService.fetchPatientPersonalDetails(FHIR_ID)).thenThrow(new AppException(
                    ResponseCode.UPSTREAM_UNAVAILABLE, "The EHR provider could not be reached."));
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
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));

            verify(patientAccessRequestService).createAccessRequest(
                    eq(FHIR_ID), eq(PATIENT_ID), eq(null), eq(null), eq(null),
                    eq("prov-9"), eq("tin-9"), any(), eq("enc-6"), eq(null), any(), any());
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** Stubs the EHR walk: personal details, doctor, clinic. */
    private void stubEhrLookups() {
        when(ehrDataService.fetchPatientPersonalDetails(FHIR_ID)).thenReturn(personalDetails());

        Clinic clinic = new Clinic();
        clinic.setClinic_id(101);
        clinic.setTax_identification_number("TIN-FROM-EHR");

        DoctorDetailsData doctor = new DoctorDetailsData();
        doctor.setDoctor_id(42);
        doctor.setClinics(List.of(clinic));

        when(ehrDataService.fetchDoctorDetails(anyInt())).thenReturn(doctor);
        when(ehrDataService.fetchClinicDetails(anyInt())).thenReturn(clinic);
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
