package com.onc.EHR.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PatientAddress {
    private String line1;
    private String line2;
    private String landmark;
    private String city;
    private String state;
    private String postal_code;
    private String country;
    private String prev_address1;
    private String prev_address2;
    private String prevCity;
    private String prevState;
    private String prevZip;
    private String prevCountry;
}