package com.onc.C2.dto;

import com.onc.EHR.dto.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Wrapper DTO to hold patient data with measure evaluation results
 * Used for QRDA-III summary generation
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PatientMeasureData {
    private String patientId;
    private PersonalDetailsData personalDetailsData;
    private List<InsuranceDetails> insuranceDetails;
    private AppointmentData appointmentData;
    private String clinicId;
    private String measureId;
    private String measureName;
    
    // Measure evaluation results
    private boolean inInitialPopulation; // IPOP: Patient is 65+ years old at measurement period start
    private boolean eligibleEncounter;
    private boolean c2Denominator;
    private boolean c2Numerator;
    private boolean denominatorExcluded;
    private boolean receivedRequiredIntervention;
    
    // Extracted data for measure calculation
    private List<EncounterData> encounters;
    private List<FormResponse> formResponses; // Store FormResponse instead of extracted AssessmentData/InterventionData
    
    /**
     * Encounter data extracted from AppointmentData
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EncounterData {
        private String id;
        private String code;
        private String codeSystem;
        private String description;
        private java.time.LocalDateTime startDate;
        private java.time.LocalDateTime endDate;
        private String status;
    }
}


