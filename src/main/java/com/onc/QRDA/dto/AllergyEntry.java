package com.onc.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AllergyEntry {
    private String substance;
    private String reaction;
    private String severity;
    private String startDate;
    private List<GenericItem> concernStatus;
}
