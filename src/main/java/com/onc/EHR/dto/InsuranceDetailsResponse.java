package com.onc.EHR.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class InsuranceDetailsResponse {
    private int code;
    private List<InsuranceDetails> data;
    private String message;
}
