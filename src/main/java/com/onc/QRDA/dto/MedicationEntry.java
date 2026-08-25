package com.onc.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MedicationEntry {
    private String medicationName;
    private String startDate;
    private String endDate;
    private List<GenericItem> route;
    private String frequency;
    private String dose;
    private String indication;
    private List<GenericItem> dispenseData;
    private String fillStatus;
}
