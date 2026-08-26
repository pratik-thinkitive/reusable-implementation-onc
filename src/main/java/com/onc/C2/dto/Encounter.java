package com.onc.C2.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Encounter {
    private String id;
    private String code;
    private String codeSystem;
    private String codeSystemName;
    private String description;
    private String startDate;
    private String endDate;
    private String status;
}
