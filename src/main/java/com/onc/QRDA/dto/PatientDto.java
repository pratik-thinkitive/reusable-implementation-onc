package com.onc.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PatientDto {
    private String patientId;
    private String firstName;
    private String middleInitial;
    private String lastName;
    private String suffix;
    private String email;
    private String workPhone;
    private String homePhone;
    private String gender;
    private Date dob;
    private Boolean deceased;
    private Date deceasedDate;
    private String maritalStatus;
    private String birthGender;
    private String preferredLanguage;
    private String tribalAffiliation;
    private String tribalAffiliationId;
    private String genderIdentityId;
    private String emergencyContactId;
    private String prefix;
    private String prevName;
    private String extId;
    private PatientAddress address;
    private String relation;
    private String organisationId;
}
