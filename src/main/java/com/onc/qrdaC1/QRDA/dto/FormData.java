package com.onc.qrdaC1.QRDA.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FormData {
    @JsonProperty("created_date")
    private String createdDate;
    
    @JsonProperty("updated_date")
    private String updatedDate;
    
    @JsonProperty("submission_id")
    private String submissionId;
    
    @JsonProperty("patient_id")
    private int patientId;
    
    @JsonProperty("organisation_id")
    private int organisationId;
    
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
    
    private FormResponse response;

    private String category;

    private int version;
    
    @JsonProperty("updated_by_user_name")
    private String updatedByUserName;

    @JsonProperty("metadata")
    private FormMetadata metadata;
}
