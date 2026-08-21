package com.onc.qrdaC1.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ImagingReportEntry {
    private String reportDescription;
    private String fileUploadSection;
}
