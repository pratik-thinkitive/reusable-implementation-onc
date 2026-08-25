package com.onc.QRDA.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PatientInformation {
    @JsonProperty("first_name")
    private String firstName;

    @JsonProperty("middle_name")
    private String middleName;

    @JsonProperty("last_name")
    private String lastName;

    @JsonProperty("preferred_name")
    private String preferredName;

    private String gender;

    @JsonProperty("what_pronouns_do_you_use?")
    private String pronouns;

    @JsonProperty("birth_date")
    private String birthDate;

    @JsonProperty("marital_status")
    private List<String> maritalStatus;

    @JsonProperty("search_location")
    private String searchLocation;

    @JsonProperty("address_line_1")
    private String addressLine1;

    @JsonProperty("address_line_2")
    private String addressLine2;

    private String city;
    private String state;

    @JsonProperty("zip")
    private String zipCode;

    @JsonProperty("is_this_your_primary_residence?")
    private String primaryResidence;

    @JsonProperty("email_id")
    private String email;

    private List<Phone> phone;

    @JsonProperty("emergency_contact")
    private List<EmergencyContact> emergencyContacts;

    @JsonProperty("race_with_more_granular_race_code")
    private String race;

    private String ethnicity;

    @JsonProperty("date_of_death")
    private String dateOfDeath;

    @JsonProperty("previous_address")
    private String previousAddress;

    @JsonProperty("tribal_affiliation")
    private String tribalAffiliation;

    @JsonProperty("related_person’s_relationship_(to_add_field_with_emergency_contact_field)")
    private String relatedPersonRelationship;

    @JsonProperty("organisation_id")
    private Integer organisationId;

    @JsonProperty("patient_id")
    private Integer patientId;

}
