package com.onc.qrdaC1.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VitalsEntry {
    private String systolic;
    private String diastolic;
    private String heartRate;
    private String respiratoryRate;
    private String bodyTemperature;
    private String pulseOximetry;
    private String oxygenConcentration;
    private String weightForLengthPercentile;
    private String headCircumferencePercentile;
}
