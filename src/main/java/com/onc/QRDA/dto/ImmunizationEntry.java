package com.onc.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ImmunizationEntry {
    private String vaccineName;
    private String dateOfVaccination;
    private List<GenericItem> status;
    private String additionalNotes;
}
