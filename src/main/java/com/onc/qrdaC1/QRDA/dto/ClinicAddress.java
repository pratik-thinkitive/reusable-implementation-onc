package com.onc.qrdaC1.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ClinicAddress {
    private String  line1;
    private String city;
    private String state;
    private String country;
    private String postal_code;
}
