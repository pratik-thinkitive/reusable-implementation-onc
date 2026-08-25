package com.onc.EHR.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetaDataPD {
    @JsonProperty("form_id")
    private String formId;

    @JsonProperty("exist_dot")
    private boolean existDot;

    private int version;

    @JsonProperty("soap_context_id")
    private String soapContextId;
}
