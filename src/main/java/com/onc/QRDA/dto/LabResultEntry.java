package com.onc.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LabResultEntry {
    private List<GenericItem> resultName;
    private String date;
}
