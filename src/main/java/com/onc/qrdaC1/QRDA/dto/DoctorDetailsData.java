package com.onc.qrdaC1.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DoctorDetailsData {
    private int doctor_id;
    private String first_name;
    private String middle_name;
    private String last_name;
    private String photo_url;
    private String sex;
    private String master_specialization;
    private String license;
    private String email;
    private String mobile;
    private String npi;
    private String name;
    private String cms_certificate_number;
    private String tax_id_number;
    private String taxonomy_code;
    private DoctorAddress residential_address;
    private List<String> qualifications;
    private List<String> roles;
    private List<String> languages;
    private List<Clinic> clinics;
}
