package com.onc.QRDA.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // Ensure this is present
public class PersonalDetailsResponseBlock {

    @JsonProperty("Patient information")
    private Map<String, PatientInformation> patientInformation;

    @JsonProperty("Care Team Members((Primary Care Provider, (Professional nurse)")
    private Map<String, CareTeamMember> careTeamMembers;
}
