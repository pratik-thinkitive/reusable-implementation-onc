package com.onc.G2.controller;

import com.onc.G2.service.PatientAccessDataService;
import com.onc.G2.service.PatientAccessRequestService;
import com.onc.G2.service.impl.PatientAccessAdminServiceImpl;
import com.onc.config.ConfigurationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The other side of the {@code onc.expose-error-details} switch.
 *
 * <p>Its default is asserted in {@link EnvelopeContractTest}; this proves the flag is actually
 * wired, so the default being safe is a real guarantee rather than a dead property.
 */
@WebMvcTest(PatientAccessAdminController.class)
@Import({ConfigurationService.class, PatientAccessAdminServiceImpl.class})
@TestPropertySource(properties = "onc.expose-error-details=true")
class VerboseErrorDetailsTest {

    private static final String BASE = "/ehr/admin/patient-access";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientAccessRequestService patientAccessRequestService;

    @MockitoBean
    private PatientAccessDataService patientAccessDataService;

    @Test
    @DisplayName("locally, the 500 message names the cause")
    void exposesCause() throws Exception {
        when(patientAccessDataService.getAllPatientData(any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(get(BASE + "/data/all")
                        .param("reportingPeriodStart", "2026-01-01")
                        .param("reportingPeriodEnd", "2026-12-31"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("IllegalStateException: boom"));
    }
}
