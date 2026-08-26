package com.onc.C2.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An intervention read out of an imported QRDA document.
 *
 * <p>Filled from two places: Intervention Order and Intervention Performed acts (templates
 * {@code …24.3.31} and {@code …24.3.32}), and any observation coded in SNOMED CT.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Intervention {
    private String id;
    private String code;
    private String status;
    private String startDate;
    private String endDate;
}
