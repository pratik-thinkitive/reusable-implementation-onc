package com.onc.C2.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PatientData {
    private String firstName;
    private String lastName;
    private String fullName;
    private String dateOfBirth;
    private String gender;
    private String race;
    private String ethnicity;
    private String streetAddress;
    private String city;
    private String state;
    private String postalCode;
    private String country;
    private List<Encounter> encounters;
    private List<Insurance> insurances;
    private List<Provider> providers;
    private List<Assessment> assessments;
    private List<Intervention> interventions;
    private String documentId;
    private String creationTime;
    private String documentTitle;
}
