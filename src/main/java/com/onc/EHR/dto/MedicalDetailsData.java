package com.onc.EHR.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MedicalDetailsData {
    private String submission_id;
    private int patient_id;
    private int organisation_id;
    private int created_by;
    private int updated_by;
    private String created_at;
    private String updated_at;
    private int appointment_id;
    private String form_name;
    private String template_name;
    private String digest_definition_id;
    private String unique_form_id;
    private int version;
    private String updated_by_user_name;

    /** Wire name is fixed by the upstream API and must not change. */
    @JsonProperty("spry_case_id")
    private String caseId;

    private String specialty;

    private Metadata metadata;

    private Map<String, Object> response;
}
