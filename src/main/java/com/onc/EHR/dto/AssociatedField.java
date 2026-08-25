package com.onc.EHR.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AssociatedField {
    private String fieldName;   // e.g., "Value" or "Result"

    private String fieldValue;  // e.g., "5.6"

    private String fieldUnit;   // e.g., "mg/dL"

    private String fieldCode;   // optional – if API provides LOINC or SNOMED code
}
