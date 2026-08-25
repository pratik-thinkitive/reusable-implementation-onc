package com.onc.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InsurancePayer {
    private Long payer_reg_id;
    private String name;
    private String payer_type;
    private String payer_id;
}
