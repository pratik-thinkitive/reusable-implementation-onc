package com.onc.G2.controller;

import com.jayway.jsonpath.JsonPath;
import com.onc.G2.service.PatientAccessDataService;
import com.onc.G2.service.PatientAccessRequestService;
import com.onc.G2.service.impl.PatientAccessAdminServiceImpl;
import com.onc.api.GlobalExceptionHandler;
import com.onc.api.support.ApiResponse;
import com.onc.config.ConfigurationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The envelope contract itself, independent of any one endpoint's behaviour.
 *
 * <p>The cases that matter most are the ones the framework answers on its own - an unknown route,
 * a wrong method, an unreadable body. Those are built by {@link GlobalExceptionHandler}'s base
 * class as RFC 7807 {@code ProblemDetail} bodies, and only the {@code handleExceptionInternal}
 * override re-shapes them. Without that override they silently ship a different shape from the
 * rest of the API, so each is pinned here.
 */
@WebMvcTest(PatientAccessAdminController.class)
@Import({ConfigurationService.class, PatientAccessAdminServiceImpl.class})
class EnvelopeContractTest {

    private static final String BASE = "/ehr/admin/patient-access";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientAccessRequestService patientAccessRequestService;

    @MockitoBean
    private PatientAccessDataService patientAccessDataService;

    @Nested
    @DisplayName("framework-generated failures still use the envelope, not ProblemDetail")
    class ProblemDetailGuard {

        @Test
        @DisplayName("unknown route")
        void unknownRoute() throws Exception {
            MockHttpServletResponse response = mockMvc.perform(get(BASE + "/no-such-endpoint"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(404);
            assertIsEnvelope(response.getContentAsString());
            assertThat(JsonPath.parse(response.getContentAsString()).read("$.code", String.class))
                    .isEqualTo("NOT_FOUND");
        }

        @Test
        @DisplayName("wrong HTTP method")
        void wrongMethod() throws Exception {
            MockHttpServletResponse response = mockMvc.perform(post(BASE + "/data/all"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(405);
            assertIsEnvelope(response.getContentAsString());
            assertThat(JsonPath.parse(response.getContentAsString()).read("$.code", String.class))
                    .isEqualTo("METHOD_NOT_ALLOWED");
        }

        /** A ProblemDetail body would carry "type"/"title"/"status"/"detail" instead. */
        @SuppressWarnings("unchecked")
        private void assertIsEnvelope(String body) {
            assertThat(body).isNotEmpty();
            Map<String, Object> parsed = (Map<String, Object>) JsonPath.parse(body).read("$", Map.class);
            assertThat(parsed).containsKeys("success", "code", "message", "path", "requestId", "version", "timestamp");
            assertThat(parsed).doesNotContainKeys("type", "title", "detail", "instance");
            assertThat(parsed.get("success")).isEqualTo(false);
        }
    }

    @Nested
    @DisplayName("request validation")
    class Validation {

        @Test
        @DisplayName("missing required parameter: 400 naming it, keyed under errors")
        void missingParameter() throws Exception {
            mockMvc.perform(get(BASE + "/data/all").param("reportingPeriodStart", "2026-01-01"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Required parameter 'reportingPeriodEnd' is missing."))
                    .andExpect(jsonPath("$.errors.reportingPeriodEnd").exists());
        }

        @Test
        @DisplayName("unparseable date: 400 saying what the format is")
        void badDate() throws Exception {
            mockMvc.perform(get(BASE + "/data/all")
                            .param("reportingPeriodStart", "2026-01-01")
                            .param("reportingPeriodEnd", "not-a-date"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                            "'reportingPeriodEnd' must be a valid date in yyyy-MM-dd format."));
        }

        @Test
        @DisplayName("path variable of the wrong type: 400, and no Java class name in the body")
        void badPathVariableType() throws Exception {
            String body = mockMvc.perform(post(BASE + "/grant-access/not-a-number"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("java.lang").doesNotContain("NumberFormatException");
        }
    }

    @Nested
    @DisplayName("500s never echo the exception")
    class ServerErrors {

        @Test
        @DisplayName("with expose-error-details off (the default), the message is the generic one")
        void genericMessage() throws Exception {
            when(patientAccessDataService.getAllPatientData(any(), any()))
                    .thenThrow(new IllegalStateException("connection string: postgres://secret@host/db"));

            String body = mockMvc.perform(get(BASE + "/data/all")
                            .param("reportingPeriodStart", "2026-01-01")
                            .param("reportingPeriodEnd", "2026-12-31"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("Something went wrong. Please try again later."))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .doesNotContain("postgres")
                    .doesNotContain("IllegalStateException")
                    .doesNotContain("at com.onc");
        }
    }

    @Nested
    @DisplayName("envelope invariants")
    class Invariants {

        @Test
        @DisplayName("success carries data and no errors; failure carries neither")
        void dataAndErrorsAreExclusive() throws Exception {
            when(patientAccessDataService.getAllPatientData(any(), any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/data/all")
                            .param("reportingPeriodStart", "2026-01-01")
                            .param("reportingPeriodEnd", "2026-12-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.errors").doesNotExist());

            when(patientAccessRequestService.getPendingRequests(any(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("boom"));

            mockMvc.perform(get(BASE + "/pending-requests")
                            .param("organisationId", "7")
                            .param("providerId", "p")
                            .param("tinId", "t"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andExpect(jsonPath("$.errors").doesNotExist());
        }

        @Test
        @DisplayName("every response carries path, requestId, version and timestamp")
        void metadataIsAlwaysPresent() throws Exception {
            when(patientAccessDataService.getAllPatientData(any(), any())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/data/all")
                            .param("reportingPeriodStart", "2026-01-01")
                            .param("reportingPeriodEnd", "2026-12-31"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.path").value(BASE + "/data/all"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty())
                    .andExpect(jsonPath("$.version").value(ApiResponse.DEFAULT_VERSION))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }

        @Test
        @DisplayName("a failure never claims success")
        void successAgreesWithStatus() throws Exception {
            mockMvc.perform(get(BASE + "/request/not-a-number"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }
}
