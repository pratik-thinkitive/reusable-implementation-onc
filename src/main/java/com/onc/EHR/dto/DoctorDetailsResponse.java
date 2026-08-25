package com.onc.EHR.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DoctorDetailsResponse {
    private int code;
    private DoctorDetailsData data;
    private String message;
}
