package com.onc.EHR.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ScreeningAssessmentEntry {
    private List<GenericItem> socialDeterminants;
    private List<GenericItem> functionalStatus;
    private List<GenericItem> disabilityStatus;
    private List<GenericItem> cognitiveStatus;
}
