package com.onc.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProblemEntry {
    private List<GenericItem> problemsNames;
    private String diagnosisDate;
    private List<GenericItem> status;
}
