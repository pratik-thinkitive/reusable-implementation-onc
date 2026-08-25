package com.onc.EHR.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class PersonalDetailsData {

    @JsonProperty("submission_id")
    private String submissionId;

    @JsonProperty("patient_id")
    private Integer patientId;

    @JsonProperty("organisation_id")
    private Integer organisationId;

    @JsonProperty("created_by")
    private int createdBy;

    @JsonProperty("updated_by")
    private int updatedBy;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    @JsonProperty("appointment_id")
    private int appointmentId;

    @JsonProperty("form_name")
    private String formName;

    @JsonProperty("template_name")
    private String templateName;

    @JsonProperty("digest_definition_id")
    private String digestDefinitionId;

    private boolean complete;

    private MetaDataPD metadata;
    private PersonalDetailsResponseBlock response;

    @JsonProperty("unique_form_id")
    private String uniqueFormId;

    private int version;

    @JsonProperty("biller_mail_sent")
    private boolean billerMailSent;

    @JsonProperty("mandatory_fields_completed")
    private boolean mandatoryFieldsCompleted;

    @JsonProperty("updated_by_user_name")
    private String updatedByUserName;

    @JsonProperty("spry_case_id")
    private String caseId;

    private String specialty;
}
