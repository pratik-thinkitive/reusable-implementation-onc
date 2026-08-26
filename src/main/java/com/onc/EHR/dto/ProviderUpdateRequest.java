package com.onc.EHR.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderUpdateRequest {
    private Integer id;
    
    @JsonProperty("first_name")
    private String firstName;
    
    @JsonProperty("middle_name")
    private String middleName;
    
    @JsonProperty("last_name")
    private String lastName;
    
    private List<String> roles;
    
    @JsonProperty("photo_url")
    private String photoUrl;
    
    @JsonProperty("doctor_id")
    private Integer doctorId;
    
    private String sex;
    
    @JsonProperty("master_specialization")
    private String masterSpecialization;
    
    private String mobile;
    
    private String email;
    
    @JsonProperty("alternative_email")
    private String alternativeEmail;
    
    @JsonProperty("organisation_id")
    private Integer organisationId;
    
    private String npi;
    
    @JsonProperty("tax_id_number")
    private String taxIdNumber;
    
    @JsonProperty("cms_certificate_number")
    private String cmsCertificateNumber;
    
    @JsonProperty("residential_address")
    private DoctorAddress residentialAddress;
    
    private List<Clinic> clinics;
}

