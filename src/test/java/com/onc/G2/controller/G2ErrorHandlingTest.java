package com.onc.G2.controller;

import com.onc.G2.exception.G2ExceptionHandler;
import com.onc.G2.service.PatientAccessDataService;
import com.onc.G2.service.PatientAccessRequestService;
import com.onc.G2.service.impl.PatientAccessAdminServiceImpl;
import com.onc.config.ConfigurationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Pins the exact error responses the G2 endpoints produce.
 *
 * <p>These assertions exist because moving error handling into {@link G2ExceptionHandler} is easy
 * to get subtly wrong. A catch-all {@code @ExceptionHandler(Exception.class)} runs before
 * Spring's own handling, so without care it turns Spring's 4xx "you sent us a bad request" into a
 * 500 "we broke". The first test below is the one that caught exactly that.
 *
 * <p>Bodies are asserted as raw strings, because "empty" is part of the contract here and is not
 * something a JSON matcher can express.
 */
@WebMvcTest(PatientAccessAdminController.class)
@Import({ConfigurationService.class, PatientAccessAdminServiceImpl.class})
class G2ErrorHandlingTest {

    private static final String BASE = "/ehr/admin/patient-access";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientAccessRequestService patientAccessRequestService;

    @MockitoBean
    private PatientAccessDataService patientAccessDataService;

    @Test
    @DisplayName("a date that will not parse stays a 400 with an empty body")
    void badDateStays400() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get(BASE + "/data/all")
                        .param("reportingPeriodStart", "2026-01-01")
                        .param("reportingPeriodEnd", "2026-31-12"))
                .andReturn().getResponse();

        // Arrives as a TypeMismatchException, which - unlike most Spring MVC exceptions - does
        // not implement ErrorResponse. The handler has to recognise it anyway.
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("a missing required parameter stays a 400 with an empty body")
    void missingParamStays400() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get(BASE + "/data/all")
                        .param("reportingPeriodStart", "2026-01-01"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("a failing listing gives 500 with an empty body")
    void listingFailureIs500WithEmptyBody() throws Exception {
        when(patientAccessRequestService.getPendingRequests(any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        MockHttpServletResponse response = mockMvc.perform(get(BASE + "/pending-requests")
                        .param("organisationId", "7")
                        .param("providerId", "prov-9")
                        .param("tinId", "tin-9"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("a failing dashboard gives 500 with an empty body")
    void dashboardFailureIs500WithEmptyBody() throws Exception {
        when(patientAccessDataService.getAccessGrantedPatientsFiltered(any(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        MockHttpServletResponse response = mockMvc.perform(get(BASE + "/dashboard/patients-with-access")
                        .param("organisationId", "7")
                        .param("providerId", "prov-9")
                        .param("tinId", "tin-9")
                        .param("reportingPeriodStart", "2026-01-01")
                        .param("reportingPeriodEnd", "2026-12-31"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("a failing grant gives 500 carrying the operation name, with null id and status")
    void grantFailureCarriesOperationName() throws Exception {
        when(patientAccessRequestService.grantAccess(anyLong()))
                .thenThrow(new RuntimeException("boom"));

        MockHttpServletResponse response = mockMvc.perform(post(BASE + "/grant-access/5"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        // requestId and status stay null - the controller never filled them on this path either.
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"success\":false,\"message\":\"Error granting access: boom\","
                        + "\"requestId\":null,\"status\":null}");
    }

    @Test
    @DisplayName("a failing revoke gives 500 carrying its own operation name")
    void revokeFailureCarriesOperationName() throws Exception {
        when(patientAccessRequestService.revokeAccess(anyLong()))
                .thenThrow(new RuntimeException("boom"));

        MockHttpServletResponse response = mockMvc.perform(post(BASE + "/revoke-access/5"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("Error revoking access: boom");
    }
}
