package com.onc.C2.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Insurance {
    private String id;
    private String payerCode;
    private String startDate;
    private String endDate;
}
