package com.onc.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Clinic {
    private int clinic_id;
    private String name;
    private ClinicAddress address;
    private String phone_number;
}
