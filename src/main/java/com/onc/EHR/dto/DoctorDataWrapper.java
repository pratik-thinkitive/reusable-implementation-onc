package com.onc.EHR.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DoctorDataWrapper {
    private int total;
    private int pages;
    private int current;
    private int no_of_records;
    private List<DoctorDetailsData> doctors;
}
