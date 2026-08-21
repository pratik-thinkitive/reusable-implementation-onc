package com.onc.qrdaC1.QRDA.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReferredItem {
    private String itemName;
    private String id;
    private String value_ref_id;
    private String name;
}
