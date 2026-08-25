package com.onc.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DoctorAddress {
    private String line1;
    private String city;
    private String state;
    private String postal_code;
    private String country;
}
