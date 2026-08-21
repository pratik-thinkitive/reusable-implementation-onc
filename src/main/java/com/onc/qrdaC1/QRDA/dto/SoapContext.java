package com.onc.qrdaC1.QRDA.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SoapContext {
    @JsonProperty("context_id")
    private String contextId;

    @JsonProperty("patient_id")
    private int patientId;

    @JsonProperty("organisation_id")
    private int organisationId;

    @JsonProperty("clinic_id")
    private int clinicId;

    private String type;

    @JsonProperty("rendering_physician")
    private Integer renderingPhysician;

    @JsonProperty("supervising_physician")
    private Integer supervisingPhysician;

    @JsonProperty("billing_provider_id")
    private String billingProviderId;

    @JsonProperty("appointment_id")
    private Integer appointmentId;

    @JsonProperty("subjective_submission_id")
    private String subjectiveSubmissionId;

    @JsonProperty("objective_submission_id")
    private String objectiveSubmissionId;

    @JsonProperty("assessment_submission_id")
    private String assessmentSubmissionId;

    @JsonProperty("treatment_sheet_id")
    private Integer treatmentSheetId;
}
