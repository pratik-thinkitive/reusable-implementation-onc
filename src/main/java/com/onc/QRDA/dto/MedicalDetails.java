package com.onc.QRDA.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MedicalDetails {

    @JsonProperty("Medical Details")
    private Map<String, ImmunizationEntry> immunizationEntry;

    @JsonProperty("Vitals")
    private Map<String, VitalsEntry> vitals;

    @JsonProperty("Allergy")
    private Map<String, AllergyEntry> allergy;

    @JsonProperty("Medication")
    private Map<String, MedicationEntry> medication;

    @JsonProperty("Smoking Status")
    private Map<String, SmokingStatusEntry> smokingStatus;

    @JsonProperty("Laboratory Test")
    private Map<String, LaboratoryTestEntry> laboratoryTest;

    @JsonProperty("Laboratory Values/Results")
    private Map<String, LabResultEntry> laboratoryValues;

    @JsonProperty("Clinican Test and Clinical Result")
    private Map<String, ClinicalTestEntry> clinicalTests;

    @JsonProperty("Diagnostic Imaging Report ")
    private Map<String, ImagingReportEntry> imagingReports;

    @JsonProperty("Implantable Device")
    private Map<String, ImplantableDeviceEntry> implantableDevices;

    @JsonProperty("Screening Assessment")
    private Map<String, ScreeningAssessmentEntry> screeningAssessment;

    @JsonProperty("Problems")
    private Map<String, ProblemEntry> problems;

    @JsonProperty("Procedures")
    private Map<String, ProcedureEntry> procedures;

    @JsonProperty("Pregnancy Status")
    private Map<String, PregnancyStatusEntry> pregnancyStatus;

    @JsonProperty("Heath Concern")
    private Map<String, HealthConcernEntry> healthConcern;
}
