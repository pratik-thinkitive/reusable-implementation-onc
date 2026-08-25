package com.onc.G2.controller;

import com.jayway.jsonpath.JsonPath;
import com.onc.G2.dto.PatientAccessDataDto;
import com.onc.G2.dto.PatientAccessRequestDto;
import com.onc.G2.enums.RequestStatus;
import com.onc.G2.enums.RequestType;
import com.onc.G2.service.PatientAccessDataService;
import com.onc.G2.service.PatientAccessRequestService;
import com.onc.G2.service.impl.PatientAccessAdminServiceImpl;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Locks in what these endpoints answer. Payloads live under {@code $.data} in the envelope. */
@WebMvcTest(PatientAccessAdminController.class)
@Import({ConfigurationService.class, PatientAccessAdminServiceImpl.class})
class PatientAccessAdminControllerTest {

    private static final String BASE = "/ehr/admin/patient-access";
    private static final String FHIR_ID = "12-3456";
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 12, 31);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientAccessRequestService patientAccessRequestService;

    @MockitoBean
    private PatientAccessDataService patientAccessDataService;

    // ------------------------------------------------------------------ request listings

    @Nested
    @DisplayName("request listing endpoints")
    class Listings {

        @Test
        @DisplayName("pending / granted / revoked each delegate to their own service call")
        void listingsDelegate() throws Exception {
            when(patientAccessRequestService.getPendingRequests(7, "prov-9", "tin-9"))
                    .thenReturn(List.of(requestDto(1L, RequestStatus.PENDING)));
            when(patientAccessRequestService.getGrantedRequests(7, "prov-9", "tin-9"))
                    .thenReturn(List.of(requestDto(2L, RequestStatus.ACCESS_GRANTED)));
            when(patientAccessRequestService.getRevokedRequests(7, "prov-9", "tin-9"))
                    .thenReturn(List.of(requestDto(3L, RequestStatus.ACCESS_REVOKED)));

            mockMvc.perform(listing("/pending-requests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value("ENTITY"))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].status").value("PENDING"));

            mockMvc.perform(listing("/access-granted"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].status").value("ACCESS_GRANTED"));

            mockMvc.perform(listing("/access-revoked"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].status").value("ACCESS_REVOKED"));
        }

        @Test
        @DisplayName("on failure: 500 in the envelope, with no trace of the cause")
        void listingFailureIsEnveloped() throws Exception {
            when(patientAccessRequestService.getPendingRequests(any(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("boom"));

            String body = mockMvc.perform(listing("/pending-requests"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("Something went wrong. Please try again later."))
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("boom").doesNotContain("RuntimeException");
        }

        private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder listing(String path) {
            return get(BASE + path)
                    .param("organisationId", "7")
                    .param("providerId", "prov-9")
                    .param("tinId", "tin-9");
        }
    }

    @Nested
    @DisplayName("single request lookup")
    class Lookup {

        @Test
        @DisplayName("found: 200 with the request under data")
        void found() throws Exception {
            when(patientAccessRequestService.getAccessRequestById(5L))
                    .thenReturn(requestDto(5L, RequestStatus.PENDING));

            mockMvc.perform(get(BASE + "/request/5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(5));
        }

        @Test
        @DisplayName("missing: 404 naming the id, because the service returns null")
        void notFound() throws Exception {
            when(patientAccessRequestService.getAccessRequestById(404L)).thenReturn(null);

            mockMvc.perform(get(BASE + "/request/404"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("No access request found for id 404."));
        }

        @Test
        @DisplayName("by patient: 200 with the patient's requests")
        void byPatient() throws Exception {
            when(patientAccessRequestService.getPatientAccessRequests(FHIR_ID))
                    .thenReturn(List.of(requestDto(6L, RequestStatus.PENDING)));

            mockMvc.perform(get(BASE + "/patient/" + FHIR_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(6));
        }
    }

    // ------------------------------------------------------------------ grant / revoke

    @Nested
    @DisplayName("POST /grant-access/{id}")
    class Grant {

        @Test
        @DisplayName("success: seeds the row, then counts the encounter and the access")
        void grantUpdatesCounters() throws Exception {
            Instant requestedAt = Instant.parse("2026-03-01T09:00:00Z");
            Instant grantedAt = Instant.parse("2026-03-02T09:00:00Z");

            PatientAccessRequestDto dto = requestDto(5L, RequestStatus.ACCESS_GRANTED);
            dto.setRequestedAt(requestedAt);
            dto.setAccessGrantedAt(grantedAt);
            when(patientAccessRequestService.grantAccess(5L)).thenReturn(dto);

            mockMvc.perform(post(BASE + "/grant-access/5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value("UPDATED"))
                    .andExpect(jsonPath("$.message").value("Access granted successfully"))
                    .andExpect(jsonPath("$.data.status").value("ACCESS_GRANTED"));

            verify(patientAccessDataService).initializePatientData(
                    eq(FHIR_ID), eq("3456"), eq("Ada"), eq("Lovelace"), eq(7),
                    eq("prov-9"), eq("tin-9"), eq(PERIOD_START), eq(PERIOD_END));

            // Denominator uses requestedAt - when the patient asked, during the encounter.
            verify(patientAccessDataService)
                    .updateDenominator(FHIR_ID, PERIOD_START, PERIOD_END, requestedAt);

            // Numerator uses accessGrantedAt - when the admin approved.
            verify(patientAccessDataService)
                    .updateNumerator(FHIR_ID, PERIOD_START, PERIOD_END, true, grantedAt);
        }

        @Test
        @DisplayName("no requestedAt: falls back to accessGrantedAt for the encounter date")
        void grantFallsBackToGrantedAt() throws Exception {
            Instant grantedAt = Instant.parse("2026-04-02T09:00:00Z");

            PatientAccessRequestDto dto = requestDto(6L, RequestStatus.ACCESS_GRANTED);
            dto.setRequestedAt(null);
            dto.setAccessGrantedAt(grantedAt);
            when(patientAccessRequestService.grantAccess(6L)).thenReturn(dto);

            mockMvc.perform(post(BASE + "/grant-access/6")).andExpect(status().isOk());

            verify(patientAccessDataService)
                    .updateDenominator(FHIR_ID, PERIOD_START, PERIOD_END, grantedAt);
        }

        @Test
        @DisplayName("wrong status: 409 naming the status it is in, no counter updates")
        void grantRejected() throws Exception {
            when(patientAccessRequestService.grantAccess(7L)).thenThrow(new AppException(
                    ResponseCode.STATUS_TRANSITION_BLOCKED,
                    "Only a request in PENDING status can be granted; this one is ACCESS_GRANTED."));

            mockMvc.perform(post(BASE + "/grant-access/7"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("STATUS_TRANSITION_BLOCKED"))
                    .andExpect(jsonPath("$.message").value(
                            "Only a request in PENDING status can be granted; this one is ACCESS_GRANTED."));

            verify(patientAccessDataService, never())
                    .updateNumerator(anyString(), any(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("unknown request: 404, no counter updates")
        void grantUnknown() throws Exception {
            when(patientAccessRequestService.grantAccess(anyLong())).thenThrow(new AppException(
                    ResponseCode.NOT_FOUND, "No access request found for id 8."));

            mockMvc.perform(post(BASE + "/grant-access/8"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));

            verify(patientAccessDataService, never())
                    .updateNumerator(anyString(), any(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("unexpected failure: 500 that does not name the operation or the cause")
        void grantThrows() throws Exception {
            when(patientAccessRequestService.grantAccess(anyLong())).thenThrow(new RuntimeException("boom"));

            String body = mockMvc.perform(post(BASE + "/grant-access/9"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Something went wrong. Please try again later."))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("boom").doesNotContain("granting");
        }
    }

    @Nested
    @DisplayName("POST /revoke-access/{id}")
    class Revoke {

        @Test
        @DisplayName("success: decrements the numerator for the request's period")
        void revokeDecrements() throws Exception {
            when(patientAccessRequestService.revokeAccess(9L))
                    .thenReturn(requestDto(9L, RequestStatus.ACCESS_REVOKED));

            mockMvc.perform(post(BASE + "/revoke-access/9"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Access revoked successfully"))
                    .andExpect(jsonPath("$.data.status").value("ACCESS_REVOKED"));

            verify(patientAccessDataService).decrementNumerator(FHIR_ID, PERIOD_START, PERIOD_END);
        }

        @Test
        @DisplayName("wrong status: 409 and no decrement")
        void revokeRejected() throws Exception {
            when(patientAccessRequestService.revokeAccess(10L)).thenThrow(new AppException(
                    ResponseCode.STATUS_TRANSITION_BLOCKED,
                    "Only a request in ACCESS_GRANTED status can be revoked; this one is PENDING."));

            mockMvc.perform(post(BASE + "/revoke-access/10"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("STATUS_TRANSITION_BLOCKED"));

            verify(patientAccessDataService, never()).decrementNumerator(anyString(), any(), any());
        }
    }

    // ------------------------------------------------------------------ reporting data

    @Nested
    @DisplayName("aggregate data endpoints")
    class Data {

        @Test
        @DisplayName("/data/tin passes the parsed period to the service")
        void tinData() throws Exception {
            PatientAccessDataDto aggregate = new PatientAccessDataDto();
            aggregate.setTinId("tin-9");
            aggregate.setDenominatorCount(4);
            aggregate.setNumeratorCount(3);
            aggregate.setPercentage(75.0);
            when(patientAccessDataService.getTinData("tin-9", PERIOD_START, PERIOD_END)).thenReturn(aggregate);

            mockMvc.perform(get(BASE + "/data/tin")
                            .param("tinId", "tin-9")
                            .param("reportingPeriodStart", "2026-01-01")
                            .param("reportingPeriodEnd", "2026-12-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.denominatorCount").value(4))
                    .andExpect(jsonPath("$.data.percentage").value(75.0));
        }

        @Test
        @DisplayName("/data/clinic-provider passes tin and provider through")
        void tinProviderData() throws Exception {
            PatientAccessDataDto aggregate = new PatientAccessDataDto();
            aggregate.setNumeratorCount(1);
            when(patientAccessDataService.getTinProviderData("tin-9", "prov-9", PERIOD_START, PERIOD_END))
                    .thenReturn(aggregate);

            mockMvc.perform(get(BASE + "/data/clinic-provider")
                            .param("tinId", "tin-9")
                            .param("providerId", "prov-9")
                            .param("reportingPeriodStart", "2026-01-01")
                            .param("reportingPeriodEnd", "2026-12-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.numeratorCount").value(1));
        }

        @Test
        @DisplayName("/data/all returns the per-patient rows")
        void allData() throws Exception {
            when(patientAccessDataService.getAllPatientData(PERIOD_START, PERIOD_END))
                    .thenReturn(List.of(dataDto(1, 1), dataDto(1, 0)));

            mockMvc.perform(get(BASE + "/data/all")
                            .param("reportingPeriodStart", "2026-01-01")
                            .param("reportingPeriodEnd", "2026-12-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));
        }

        @Test
        @DisplayName("dates use yyyy-MM-dd; a yyyy-dd-MM value is rejected by name")
        void rejectsTransposedDate() throws Exception {
            // yyyy-dd-MM would accept this; yyyy-MM-dd must not.
            mockMvc.perform(get(BASE + "/data/all")
                            .param("reportingPeriodStart", "2026-01-01")
                            .param("reportingPeriodEnd", "2026-31-12"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.errors.reportingPeriodEnd").exists());
        }
    }

    // ------------------------------------------------------------------ dashboards

    @Nested
    @DisplayName("dashboard endpoints")
    class Dashboards {

        @Test
        @DisplayName("per-provider: totals, percentage and exact key order inside data")
        void providerDashboard() throws Exception {
            when(patientAccessDataService.getAccessGrantedPatientsFiltered(7, "prov-9", "tin-9", PERIOD_START, PERIOD_END))
                    .thenReturn(List.of(dataDto(1, 1), dataDto(1, 0)));

            String body = mockMvc.perform(get(BASE + "/dashboard/patients-with-access")
                            .param("organisationId", "7")
                            .param("providerId", "prov-9")
                            .param("tinId", "tin-9")
                            .param("reportingPeriodStart", "2026-01-01")
                            .param("reportingPeriodEnd", "2026-12-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalDenominator").value(2))
                    .andExpect(jsonPath("$.data.totalNumerator").value(1))
                    .andExpect(jsonPath("$.data.percentage").value(50.0))
                    .andExpect(jsonPath("$.data.reportingPeriodStart").value("2026-01-01"))
                    .andExpect(jsonPath("$.data.reportingPeriodEnd").value("2026-12-31"))
                    .andReturn().getResponse().getContentAsString();

            // Key order within the payload is part of what consumers see, so it is pinned.
            assertThat(keysOf(body, "$.data")).containsExactly(
                    "patientsWithAccess", "reportingPeriodStart", "reportingPeriodEnd",
                    "totalNumerator", "totalDenominator", "percentage");
        }

        @Test
        @DisplayName("group: same shape with groupId first")
        void groupDashboard() throws Exception {
            when(patientAccessDataService.getAccessGrantedPatientsForGroup("tin-9", PERIOD_START, PERIOD_END))
                    .thenReturn(List.of(dataDto(1, 1)));

            String body = mockMvc.perform(get(BASE + "/dashboard/group-patients-with-access")
                            .param("tinId", "tin-9")
                            .param("reportingPeriodStart", "2026-01-01")
                            .param("reportingPeriodEnd", "2026-12-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.groupId").value("tin-9"))
                    .andExpect(jsonPath("$.data.percentage").value(100.0))
                    .andReturn().getResponse().getContentAsString();

            assertThat(keysOf(body, "$.data")).containsExactly(
                    "groupId", "patientsWithAccess", "reportingPeriodStart", "reportingPeriodEnd",
                    "totalNumerator", "totalDenominator", "percentage");
        }

        @Test
        @DisplayName("no matching patients: percentage is 0, not a divide-by-zero")
        void emptyDashboard() throws Exception {
            when(patientAccessDataService.getAccessGrantedPatientsFiltered(any(), anyString(), anyString(), any(), any()))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE + "/dashboard/patients-with-access")
                            .param("organisationId", "7")
                            .param("providerId", "prov-9")
                            .param("tinId", "tin-9")
                            .param("reportingPeriodStart", "2026-01-01")
                            .param("reportingPeriodEnd", "2026-12-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.percentage").value(0.0))
                    .andExpect(jsonPath("$.data.totalDenominator").value(0));
        }

        @Test
        @DisplayName("tinId is optional on the per-provider dashboard")
        void tinIsOptional() throws Exception {
            when(patientAccessDataService.getAccessGrantedPatientsFiltered(7, "prov-9", null, PERIOD_START, PERIOD_END))
                    .thenReturn(List.of(dataDto(1, 1)));

            mockMvc.perform(get(BASE + "/dashboard/patients-with-access")
                            .param("organisationId", "7")
                            .param("providerId", "prov-9")
                            .param("reportingPeriodStart", "2026-01-01")
                            .param("reportingPeriodEnd", "2026-12-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalNumerator").value(1));
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** Keys at the given path, in the order they appear. */
    @SuppressWarnings("unchecked")
    private List<String> keysOf(String json, String path) {
        return List.copyOf(((Map<String, Object>) JsonPath.parse(json).read(path, Map.class)).keySet());
    }

    private PatientAccessRequestDto requestDto(Long id, RequestStatus status) {
        PatientAccessRequestDto dto = new PatientAccessRequestDto();
        dto.setId(id);
        dto.setStatus(status);
        dto.setRequestType(RequestType.MEDICAL_DETAILS_ACCESS);
        dto.setPatientFhirId(FHIR_ID);
        dto.setPatientId("3456");
        dto.setFirstName("Ada");
        dto.setLastName("Lovelace");
        dto.setOrganisationId(7);
        dto.setProviderId("prov-9");
        dto.setTinId("tin-9");
        dto.setReportingPeriodStart(PERIOD_START);
        dto.setReportingPeriodEnd(PERIOD_END);
        return dto;
    }

    private PatientAccessDataDto dataDto(int denominator, int numerator) {
        PatientAccessDataDto dto = new PatientAccessDataDto();
        dto.setPatientFhirId(FHIR_ID);
        dto.setDenominatorCount(denominator);
        dto.setNumeratorCount(numerator);
        return dto;
    }
}
