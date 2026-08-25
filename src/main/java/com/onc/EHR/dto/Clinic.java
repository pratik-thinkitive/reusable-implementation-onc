package com.onc.EHR.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Clinic {
    private int clinic_id;
    private String name;
    private String tax_identification_number;
    private String organisation_id;
    private ClinicAddress address;
    private String phone_number;
}
