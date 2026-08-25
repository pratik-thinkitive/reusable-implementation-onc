package com.onc.QRDA.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SnomedCode {
    private String code;
    private String description;
    private int id;
    
    @JsonProperty("conceptId")
    private String conceptId;
    private String term;
    
    @JsonProperty("start_date")
    private String startDate;
    
    @JsonProperty("end_date")
    private String endDate;
    private String status;
}
