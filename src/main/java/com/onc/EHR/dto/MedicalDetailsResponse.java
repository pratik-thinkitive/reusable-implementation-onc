package com.onc.EHR.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MedicalDetailsResponse {
    private int code;
    private MedicalDetailsData data;
    private String message;
}

