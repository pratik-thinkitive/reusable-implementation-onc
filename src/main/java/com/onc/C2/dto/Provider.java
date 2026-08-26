package com.onc.C2.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Provider {
    private String firstName;
    private String lastName;
    private String npi;
    private String tin;
    private String ccn;
    private String taxonomyCode;
    private String street;
    private String city;
    private String state;
    private String postalCode;
    private String country;
}
