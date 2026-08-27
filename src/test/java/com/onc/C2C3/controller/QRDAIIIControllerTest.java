package com.onc.C2C3.controller;

import com.onc.C2C3.service.QRDAAggregationService;
import com.onc.api.support.ResponseCode;
import com.onc.common.exception.AppException;
import com.onc.config.ConfigurationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins how C2C3 answers now that its two endpoints let failures reach
 * {@link com.onc.api.GlobalExceptionHandler} instead of catching and describing them itself.
 *
 * <p>The success cases matter as much as the failures here: this module produces the artifact
 * that passed certification, so the binary responses have to keep their exact shape.
 */
@WebMvcTest(QRDAIIIController.class)
@Import(ConfigurationService.class)
class QRDAIIIControllerTest {

    private static final String IMPORT_URL = "/ehr/c2/import";
    private static final String SUMMARY_URL = "/ehr/c2/summary";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QRDAAggregationService qrdaAggregationService;

    @Nested
    @DisplayName("success responses keep their existing shape")
    class SuccessShape {

        /** Guards the removal of `produces = "application/zip"`, which would have blocked JSON errors. */
        @Test
        @DisplayName("/summary still returns the ZIP bytes, unenveloped")
        void summaryReturnsZip() throws Exception {
            byte[] zip = {0x50, 0x4B, 0x03, 0x04, 0x11, 0x22};
            doReturn(ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zip))
                    .when(qrdaAggregationService)
                    .generateQrdaIIISummary(any(), anyString(), anyString());

            byte[] body = mockMvc.perform(post(SUMMARY_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[\"12-3456\"]")
                            .param("measurementPeriodStart", "2023-01-01")
                            .param("measurementPeriodEnd", "2023-12-31"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                    .andReturn().getResponse().getContentAsByteArray();

            assertThat(body).isEqualTo(zip);
        }

        @Test
        @DisplayName("/import still returns its raw map, not the envelope")
        void importReturnsRawMap() throws Exception {
            doReturn(ResponseEntity.ok(Map.of("totalFiles", 3, "uploaded", 2)))
                    .when(qrdaAggregationService).importC2Patients(any());

            mockMvc.perform(multipart(IMPORT_URL)
                            .file(new MockMultipartFile("file", "patients.zip",
                                    "application/zip", new byte[]{0x50, 0x4B})))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalFiles").value(3))
                    .andExpect(jsonPath("$.uploaded").value(2))
                    // The envelope's own keys must be absent - this body is deliberately raw.
                    .andExpect(jsonPath("$.success").doesNotExist())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("failures now go through the global handler")
    class Failures {

        @Test
        @DisplayName("a rejected summary answers 400 in the envelope, keeping the service's wording")
        void summaryRejection() throws Exception {
            when(qrdaAggregationService.generateQrdaIIISummary(any(), anyString(), anyString()))
                    .thenThrow(new AppException(ResponseCode.BAD_REQUEST, "Patient ID list cannot be empty"));

            mockMvc.perform(post(SUMMARY_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[]")
                            .param("measurementPeriodStart", "2023-01-01")
                            .param("measurementPeriodEnd", "2023-12-31"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Patient ID list cannot be empty"))
                    .andExpect(jsonPath("$.path").value(SUMMARY_URL));
        }

        @Test
        @DisplayName("no patients found answers 404 in the envelope")
        void summaryNotFound() throws Exception {
            when(qrdaAggregationService.generateQrdaIIISummary(any(), anyString(), anyString()))
                    .thenThrow(new AppException(ResponseCode.NOT_FOUND,
                            "No valid patient data found for summary generation"));

            mockMvc.perform(post(SUMMARY_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[\"12-3456\"]")
                            .param("measurementPeriodStart", "2023-01-01")
                            .param("measurementPeriodEnd", "2023-12-31"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("No valid patient data found for summary generation"));
        }

        @Test
        @DisplayName("an unexpected summary failure is a generic 500 that does not echo the cause")
        void summaryUnexpected() throws Exception {
            when(qrdaAggregationService.generateQrdaIIISummary(any(), anyString(), anyString()))
                    .thenThrow(new IllegalStateException("jdbc:postgresql://secret@host/db"));

            String body = mockMvc.perform(post(SUMMARY_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[\"12-3456\"]")
                            .param("measurementPeriodStart", "2023-01-01")
                            .param("measurementPeriodEnd", "2023-12-31"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("Something went wrong. Please try again later."))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("postgresql").doesNotContain("IllegalStateException");
        }

        @Test
        @DisplayName("an unreadable ZIP answers 400 in the envelope, without the stream's message")
        void importRejection() throws Exception {
            when(qrdaAggregationService.importC2Patients(any()))
                    .thenThrow(new AppException(ResponseCode.BAD_REQUEST, "Failed to process ZIP",
                            new java.io.IOException("Unexpected end of ZLIB input stream")));

            String body = mockMvc.perform(multipart(IMPORT_URL)
                            .file(new MockMultipartFile("file", "broken.zip",
                                    "application/zip", new byte[]{0x50})))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Failed to process ZIP"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("ZLIB");
        }

        @Test
        @DisplayName("an unexpected import failure is a generic 500")
        void importUnexpected() throws Exception {
            when(qrdaAggregationService.importC2Patients(any()))
                    .thenThrow(new RuntimeException("boom"));

            String body = mockMvc.perform(multipart(IMPORT_URL)
                            .file(new MockMultipartFile("file", "patients.zip",
                                    "application/zip", new byte[]{0x50, 0x4B})))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("boom");
        }
    }

    @Nested
    @DisplayName("request binding")
    class Binding {

        @Test
        @DisplayName("a missing measurement period is a 400 naming the parameter")
        void missingParameter() throws Exception {
            mockMvc.perform(post(SUMMARY_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[\"12-3456\"]")
                            .param("measurementPeriodStart", "2023-01-01"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message")
                            .value("Required parameter 'measurementPeriodEnd' is missing."));
        }

        @Test
        @DisplayName("a missing file part on import is a 400 in the envelope, not a ProblemDetail")
        void missingFilePart() throws Exception {
            mockMvc.perform(multipart(IMPORT_URL))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty())
                    .andExpect(jsonPath("$.detail").doesNotExist());
        }
    }
}
