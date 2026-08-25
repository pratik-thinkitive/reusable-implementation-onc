package com.onc.EHR.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class InsuranceDetails {
    private Long insurance_card_id;
    private InsurancePayer insurance_payer;
    private String insurance_number;
    private String plan_type;
    private String plan_start_date;
    private String plan_end_date;
    private Long patient_id;
    private String payer_type;
}
